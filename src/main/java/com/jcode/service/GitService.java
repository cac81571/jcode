package com.jcode.service;

import com.jcode.model.DiffTexts;
import com.jcode.model.GitChange;
import org.eclipse.jgit.api.Git;
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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

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
        try (RevWalk walk = new RevWalk(repository)) {
            Iterable<RevCommit> commits = git.log().setMaxCount(limit).call();
            for (RevCommit commit : commits) {
                walk.parseBody(commit);
                String shortId = commit.getName().substring(0, 7);
                String msg = commit.getShortMessage();
                lines.add(shortId + "  " + msg);
            }
        }
        return lines;
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
