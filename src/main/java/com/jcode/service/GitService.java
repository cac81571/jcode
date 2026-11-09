package com.jcode.service;

import com.jcode.model.DiffTexts;
import com.jcode.model.GitChange;
import com.jcode.model.GitCommitEntry;
import com.jcode.model.GitStashEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Thin JGit facade (same engine used by EGit).
 */
public final class GitService implements Closeable {

    private final Path workspaceRoot;
    private final Repository repository;
    private final Git git;

    private GitService(Path workspaceRoot, Repository repository) {
        this.workspaceRoot = workspaceRoot;
        this.repository = repository;
        this.git = new Git(repository);
    }

    public static GitService open(Path workspaceRoot) throws IOException {
        Path gitDir = findGitDir(workspaceRoot);
        if (gitDir == null) {
            return null;
        }
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .readEnvironment()
                .findGitDir()
                .build();
        return new GitService(workspaceRoot, repo);
    }

    public static GitService init(Path workspaceRoot) throws GitAPIException, IOException {
        Files.createDirectories(workspaceRoot);
        Git.init().setDirectory(workspaceRoot.toFile()).call().close();
        return open(workspaceRoot);
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public boolean isRepository() {
        return repository != null;
    }

    public String currentBranch() throws IOException {
        return repository.getBranch();
    }

    /**
     * Local branch names (short), sorted. Current branch is not specially ordered.
     */
    public List<String> listBranches() throws GitAPIException {
        List<String> names = new ArrayList<>();
        for (Ref ref : git.branchList().call()) {
            String name = Repository.shortenRefName(ref.getName());
            names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Create a local branch from HEAD (does not switch).
     */
    public void createBranch(String name) throws GitAPIException {
        createBranch(name, null);
    }

    /**
     * Create a local branch from {@code startPoint} (commit id / ref), or HEAD if null.
     * Does not switch.
     */
    public void createBranch(String name, String startPoint) throws GitAPIException {
        String normalized = requireBranchName(name);
        var cmd = git.branchCreate().setName(normalized);
        if (startPoint != null && !startPoint.isBlank()) {
            cmd.setStartPoint(startPoint.trim());
        }
        cmd.call();
    }

    /**
     * Create a local branch from HEAD and check it out.
     */
    public void createAndCheckoutBranch(String name) throws GitAPIException {
        createAndCheckoutBranch(name, null);
    }

    /**
     * Create a local branch from {@code startPoint} (commit id / ref) and check it out.
     */
    public void createAndCheckoutBranch(String name, String startPoint) throws GitAPIException {
        String normalized = requireBranchName(name);
        var cmd = git.checkout()
                .setCreateBranch(true)
                .setName(normalized);
        if (startPoint != null && !startPoint.isBlank()) {
            cmd.setStartPoint(startPoint.trim());
        }
        cmd.call();
    }

    /**
     * Switch to an existing local branch ({@code git checkout <name>}).
     */
    public void checkoutBranch(String name) throws GitAPIException {
        String normalized = requireBranchName(name);
        git.checkout().setName(normalized).call();
    }

    /**
     * Delete a local branch. Refuses to delete the currently checked-out branch.
     *
     * @param force if true, allows deleting unmerged branches ({@code -D})
     */
    public void deleteBranch(String name, boolean force) throws GitAPIException, IOException {
        String normalized = requireBranchName(name);
        String current = currentBranch();
        if (normalized.equals(current)) {
            throw new IllegalArgumentException("現在のブランチは削除できません: " + normalized);
        }
        git.branchDelete()
                .setBranchNames(normalized)
                .setForce(force)
                .call();
    }

    private static String requireBranchName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ブランチ名が空です");
        }
        String trimmed = name.trim();
        if (trimmed.contains("..") || trimmed.contains(" ") || trimmed.startsWith("-")
                || trimmed.contains("\\") || trimmed.contains("~") || trimmed.contains("^")
                || trimmed.contains(":") || trimmed.contains("?") || trimmed.contains("*")
                || trimmed.contains("[") || trimmed.endsWith(".") || trimmed.endsWith("/")) {
            throw new IllegalArgumentException("不正なブランチ名です: " + trimmed);
        }
        return trimmed;
    }

    public List<GitChange> listChanges() throws IOException, GitAPIException {
        Status status = git.status().call();
        List<GitChange> changes = new ArrayList<>();

        status.getAdded().forEach(p -> changes.add(new GitChange(p, DiffEntry.ChangeType.ADD, true)));
        status.getChanged().forEach(p -> changes.add(new GitChange(p, DiffEntry.ChangeType.MODIFY, true)));
        status.getRemoved().forEach(p -> changes.add(new GitChange(p, DiffEntry.ChangeType.DELETE, true)));

        status.getModified().forEach(p -> changes.add(new GitChange(p, DiffEntry.ChangeType.MODIFY, false)));
        status.getUntracked().forEach(p -> changes.add(new GitChange(p, DiffEntry.ChangeType.ADD, false)));
        status.getMissing().forEach(p -> changes.add(new GitChange(p, DiffEntry.ChangeType.DELETE, false)));
        status.getConflicting().forEach(p -> changes.add(new GitChange(p, DiffEntry.ChangeType.MODIFY, false)));

        return changes;
    }

    public void stage(String path) throws GitAPIException {
        git.add().addFilepattern(normalize(path)).call();
    }

    public void unstage(String path) throws GitAPIException, IOException {
        git.reset().addPath(normalize(path)).call();
    }

    public void stageAll() throws GitAPIException {
        git.add().addFilepattern(".").call();
    }

    /**
     * Discard unstaged working-tree changes for a path (like {@code git restore -- path}
     * / delete untracked files).
     */
    public void discard(String path) throws GitAPIException, IOException {
        String normalized = normalize(path);
        Status status = git.status().addPath(normalized).call();
        if (status.getUntracked().contains(normalized)
                || status.getUntrackedFolders().stream().anyMatch(normalized::startsWith)) {
            Path target = workspaceRoot.resolve(normalized);
            deleteRecursively(target);
            return;
        }
        // Restore tracked file from index (covers modify / missing)
        git.checkout().addPath(normalized).call();
    }

    public void discardAllUnstaged() throws GitAPIException, IOException {
        Status status = git.status().call();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        paths.addAll(status.getModified());
        paths.addAll(status.getMissing());
        paths.addAll(status.getUntracked());
        paths.addAll(status.getConflicting());
        for (String folder : status.getUntrackedFolders()) {
            paths.add(folder);
        }
        for (String path : paths) {
            discard(path);
        }
    }

    public RevCommit commit(String message) throws GitAPIException {
        return git.commit().setMessage(message).call();
    }

    /**
     * Stash working tree + index changes ({@code git stash push}).
     *
     * @param message optional stash message (may be blank)
     * @param includeUntracked include untracked files
     * @return created stash commit, or null if nothing to stash
     */
    public RevCommit stashPush(String message, boolean includeUntracked) throws GitAPIException {
        var cmd = git.stashCreate().setIncludeUntracked(includeUntracked);
        if (message != null && !message.isBlank()) {
            cmd.setWorkingDirectoryMessage(message.trim());
        }
        return cmd.call();
    }

    public List<GitStashEntry> listStashes() throws GitAPIException {
        List<GitStashEntry> entries = new ArrayList<>();
        int index = 0;
        for (RevCommit commit : git.stashList().call()) {
            String shortId = commit.getName().substring(0, 7);
            String msg = commit.getShortMessage();
            entries.add(new GitStashEntry(index, "stash@{" + index + "}", shortId, msg));
            index++;
        }
        return entries;
    }

    public void stashApply(int index) throws GitAPIException {
        git.stashApply().setStashRef(stashRef(index)).call();
    }

    public void stashPop(int index) throws GitAPIException {
        stashApply(index);
        stashDrop(index);
    }

    public void stashDrop(int index) throws GitAPIException {
        git.stashDrop().setStashRef(index).call();
    }

    private static String stashRef(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("不正な stash index: " + index);
        }
        return "stash@{" + index + "}";
    }

    public String diff(String path, boolean staged) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repository);
            formatter.setContext(3);

            List<DiffEntry> diffs;
            if (staged) {
                ObjectId head = repository.resolve("HEAD^{tree}");
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                try (ObjectReader reader = repository.newObjectReader()) {
                    if (head != null) {
                        oldTree.reset(reader, head);
                    }
                }
                diffs = formatter.scan(oldTree, new DirCacheIterator(repository.readDirCache()));
            } else {
                diffs = formatter.scan(new DirCacheIterator(repository.readDirCache()), new FileTreeIterator(repository));
            }

            String normalized = normalize(path);
            for (DiffEntry entry : diffs) {
                String entryPath = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath()
                        : entry.getNewPath();
                if (normalized.equals(entryPath) || "/dev/null".equals(entryPath) && normalized.equals(entry.getOldPath())) {
                    formatter.format(entry);
                    break;
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Load old/new text for a path so a colored diff viewer can render it.
     * Staged: HEAD → index. Unstaged: index (or empty) → working tree.
     */
    public DiffTexts loadDiffTexts(String path, boolean staged) throws IOException {
        String normalized = normalize(path);
        String oldText;
        String newText;
        if (staged) {
            oldText = readFromHead(normalized);
            newText = readFromIndex(normalized);
        } else {
            String indexText = readFromIndex(normalized);
            oldText = indexText.isEmpty() ? readFromHead(normalized) : indexText;
            newText = readWorkingTree(normalized);
        }
        return new DiffTexts(normalized, oldText, newText);
    }

    /**
     * Files changed in {@code commitId} compared to its first parent (or empty tree).
     */
    public List<GitChange> listCommitChanges(String commitId) throws IOException {
        List<GitChange> changes = new ArrayList<>();
        for (DiffEntry entry : diffEntriesForCommit(commitId)) {
            String path = pathOf(entry);
            if (path == null) {
                continue;
            }
            changes.add(new GitChange(path, entry.getChangeType(), false));
        }
        return changes;
    }

    /**
     * Diff texts for a path in {@code commitId} vs its first parent.
     */
    public DiffTexts loadCommitDiffTexts(String commitId, String path) throws IOException {
        String normalized = normalize(path);
        DiffEntry match = null;
        for (DiffEntry entry : diffEntriesForCommit(commitId)) {
            if (normalized.equals(pathOf(entry))
                    || normalized.equals(entry.getOldPath())
                    || normalized.equals(entry.getNewPath())) {
                match = entry;
                break;
            }
        }
        if (match == null) {
            return new DiffTexts(normalized, "", "");
        }
        String oldText = readBlob(match.getOldId().toObjectId());
        String newText = readBlob(match.getNewId().toObjectId());
        return new DiffTexts(normalized, oldText, newText);
    }

    private List<DiffEntry> diffEntriesForCommit(String commitId) throws IOException {
        ObjectId id = repository.resolve(commitId);
        if (id == null) {
            return List.of();
        }
        try (RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            RevCommit commit = walk.parseCommit(id);
            walk.parseHeaders(commit);
            AbstractTreeIterator oldTree = treeIterator(walk, commit.getParentCount() > 0
                    ? commit.getParent(0).getId()
                    : null);
            CanonicalTreeParser newTree = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                newTree.reset(reader, commit.getTree());
            }
            return formatter.scan(oldTree, newTree);
        }
    }

    private AbstractTreeIterator treeIterator(RevWalk walk, ObjectId commitId) throws IOException {
        if (commitId == null) {
            return new EmptyTreeIterator();
        }
        RevCommit commit = walk.parseCommit(commitId);
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (ObjectReader reader = repository.newObjectReader()) {
            parser.reset(reader, commit.getTree());
        }
        return parser;
    }

    private static String pathOf(DiffEntry entry) {
        if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
            return emptyToNull(entry.getOldPath());
        }
        String path = emptyToNull(entry.getNewPath());
        return path != null ? path : emptyToNull(entry.getOldPath());
    }

    private static String emptyToNull(String path) {
        if (path == null || path.isEmpty() || DiffEntry.DEV_NULL.equals(path)) {
            return null;
        }
        return path;
    }

    private String readBlob(ObjectId objectId) throws IOException {
        if (objectId == null || ObjectId.zeroId().equals(objectId)) {
            return "";
        }
        ObjectLoader loader = repository.open(objectId);
        if (loader.getType() != org.eclipse.jgit.lib.Constants.OBJ_BLOB) {
            return "";
        }
        // Cap huge files to keep the UI responsive
        if (loader.getSize() > 2_000_000) {
            return "[binary or too large to display]";
        }
        return new String(loader.getBytes(), StandardCharsets.UTF_8);
    }

    private String readFromHead(String path) throws IOException {
        ObjectId head = repository.resolve("HEAD");
        if (head == null) {
            return "";
        }
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(head);
            try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
                if (treeWalk == null) {
                    return "";
                }
                ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                return new String(loader.getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private String readFromIndex(String path) throws IOException {
        DirCache cache = repository.readDirCache();
        DirCacheEntry entry = cache.getEntry(path);
        if (entry == null) {
            return "";
        }
        ObjectLoader loader = repository.open(entry.getObjectId());
        return new String(loader.getBytes(), StandardCharsets.UTF_8);
    }

    private String readWorkingTree(String path) throws IOException {
        Path file = workspaceRoot.resolve(path);
        if (!Files.isRegularFile(file)) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public List<String> recentCommits(int limit) throws IOException, GitAPIException {
        List<String> lines = new ArrayList<>();
        for (GitCommitEntry entry : listLog(limit)) {
            lines.add(entry.shortId() + "  " + entry.shortMessage());
        }
        return lines;
    }

    /**
     * Commit history for the current branch (newest first), with HEAD / ref labels.
     */
    public List<GitCommitEntry> listLog(int limit) throws IOException, GitAPIException {
        Map<String, List<String>> refsByCommit = collectRefsByCommit();
        ObjectId headId = repository.resolve("HEAD");
        String headName = headId != null ? headId.getName() : null;

        List<GitCommitEntry> entries = new ArrayList<>();
        Iterable<RevCommit> commits = git.log().setMaxCount(Math.max(1, limit)).call();
        for (RevCommit commit : commits) {
            List<String> parents = new ArrayList<>(commit.getParentCount());
            for (int i = 0; i < commit.getParentCount(); i++) {
                parents.add(commit.getParent(i).getName());
            }
            PersonIdent author = commit.getAuthorIdent();
            String authorName = author != null ? author.getName() : "";
            Instant when = Instant.ofEpochSecond(commit.getCommitTime());
            boolean isHead = headName != null && headName.equals(commit.getName());
            List<String> refs = refsByCommit.getOrDefault(commit.getName(), List.of());
            entries.add(new GitCommitEntry(
                    commit.getName(),
                    commit.getName().substring(0, 7),
                    commit.getShortMessage(),
                    commit.getFullMessage(),
                    authorName,
                    when,
                    List.copyOf(parents),
                    isHead,
                    refs
            ));
        }
        return entries;
    }

    public enum ResetMode {
        SOFT, MIXED, HARD
    }

    /**
     * Reset current branch tip to {@code commitId} ({@code git reset --soft/--mixed/--hard}).
     */
    public void resetTo(String commitId, ResetMode mode) throws GitAPIException, IOException {
        if (commitId == null || commitId.isBlank()) {
            throw new IllegalArgumentException("commit id が空です");
        }
        ObjectId target = repository.resolve(commitId);
        if (target == null) {
            throw new IOException("コミットが見つかりません: " + commitId);
        }
        ResetCommand.ResetType type = switch (mode) {
            case SOFT -> ResetCommand.ResetType.SOFT;
            case MIXED -> ResetCommand.ResetType.MIXED;
            case HARD -> ResetCommand.ResetType.HARD;
        };
        git.reset().setMode(type).setRef(commitId).call();
    }

    private Map<String, List<String>> collectRefsByCommit() throws IOException {
        Map<String, List<String>> map = new HashMap<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix("refs/heads/")) {
            ObjectId id = ref.getObjectId();
            if (id == null) {
                continue;
            }
            String name = ref.getName().substring("refs/heads/".length());
            map.computeIfAbsent(id.getName(), k -> new ArrayList<>()).add(name);
        }
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix("refs/tags/")) {
            ObjectId id = ref.getObjectId();
            if (id == null) {
                continue;
            }
            // Peel annotated tags when possible
            Ref peeled = repository.getRefDatabase().peel(ref);
            if (peeled.getPeeledObjectId() != null) {
                id = peeled.getPeeledObjectId();
            }
            String name = "tag: " + ref.getName().substring("refs/tags/".length());
            map.computeIfAbsent(id.getName(), k -> new ArrayList<>()).add(name);
        }
        return map;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isRegularFile(path)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path findGitDir(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path git = current.resolve(".git");
            if (Files.exists(git)) {
                return git;
            }
            current = current.getParent();
        }
        return null;
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }
}
