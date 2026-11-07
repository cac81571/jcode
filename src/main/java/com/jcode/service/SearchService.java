package com.jcode.service;

import com.jcode.model.ReplaceResult;
import com.jcode.model.SearchMatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchService {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", ".vscode", "out", "dist", ".cursor"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "xml", "md", "txt", "json", "yml", "yaml", "properties",
            "gradle", "kts", "html", "css", "js", "ts", "tsx", "jsx", "py", "go",
            "rs", "c", "h", "cpp", "hpp", "cs", "sql", "sh", "bat", "ps1", "gitignore",
            "toml", "ini", "cfg", "svg", "fxml"
    );

    private SearchService() {
    }

    public static List<SearchMatch> findInText(
            String text,
            Path file,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) {
        List<SearchMatch> matches = new ArrayList<>();
        if (query == null || query.isEmpty() || text == null) {
            return matches;
        }

        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String searchLine = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            Matcher matcher = pattern.matcher(searchLine);
            while (matcher.find()) {
                matches.add(new SearchMatch(file, i + 1, matcher.start() + 1, matcher.end() + 1, searchLine));
            }
        }
        return matches;
    }

    public static List<SearchMatch> findInWorkspace(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) throws IOException {
        return findInWorkspace(root, query, matchCase, wholeWord, regex, null, null);
    }

    public static List<SearchMatch> findInWorkspace(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String filesToInclude,
            String filesToExclude
    ) throws IOException {
        List<SearchMatch> matches = new ArrayList<>();
        if (root == null || !Files.isDirectory(root) || query == null || query.isEmpty()) {
            return matches;
        }

        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        FilePathFilter pathFilter = FilePathFilter.parse(filesToInclude, filesToExclude);
        Path absoluteRoot = root.toAbsolutePath().normalize();

        Files.walkFileTree(absoluteRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(absoluteRoot) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (pathFilter.skipDirectory(absoluteRoot, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > 2_000_000) {
                    return FileVisitResult.CONTINUE;
                }
                if (!pathFilter.accept(absoluteRoot, file)) {
                    return FileVisitResult.CONTINUE;
                }
                // When include globs are set, trust them; otherwise keep text-extension filter
                if (!pathFilter.hasIncludes() && !isLikelyText(file)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    matches.addAll(findInText(content, file, pattern));
                } catch (IOException ignored) {
                    // binary / unreadable
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return matches;
    }

    public static String replaceInText(
            String text,
            String query,
            String replacement,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            boolean replaceAll
    ) {
        if (query == null || query.isEmpty() || text == null) {
            return text;
        }
        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        Matcher matcher = pattern.matcher(text);
        String safeReplacement = Matcher.quoteReplacement(replacement == null ? "" : replacement);
        return replaceAll ? matcher.replaceAll(safeReplacement) : matcher.replaceFirst(safeReplacement);
    }

    /**
     * Replace all matches in the given files. Returns how many files / occurrences changed.
     */
    public static ReplaceResult replaceInFiles(
            Collection<Path> files,
            String query,
            String replacement,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) throws IOException {
        if (files == null || files.isEmpty() || query == null || query.isEmpty()) {
            return new ReplaceResult(0, 0, List.of());
        }
        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        String safeReplacement = Matcher.quoteReplacement(replacement == null ? "" : replacement);
        List<Path> changed = new ArrayList<>();
        int totalReplacements = 0;

        for (Path file : new LinkedHashSet<>(files)) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String original = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = pattern.matcher(original);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count == 0) {
                continue;
            }
            String updated = pattern.matcher(original).replaceAll(safeReplacement);
            if (!original.equals(updated)) {
                Files.writeString(file, updated, StandardCharsets.UTF_8);
                changed.add(file.toAbsolutePath().normalize());
                totalReplacements += count;
            }
        }
        return new ReplaceResult(changed.size(), totalReplacements, List.copyOf(changed));
    }

    private static List<SearchMatch> findInText(String text, Path file, Pattern pattern) {
        List<SearchMatch> matches = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String searchLine = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            Matcher matcher = pattern.matcher(searchLine);
            while (matcher.find()) {
                matches.add(new SearchMatch(file, i + 1, matcher.start() + 1, matcher.end() + 1, searchLine));
            }
        }
        return matches;
    }

    private static Pattern compile(String query, boolean matchCase, boolean wholeWord, boolean regex) {
        String patternText = regex ? query : Pattern.quote(query);
        if (wholeWord) {
            patternText = "\\b" + patternText + "\\b";
        }
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(patternText, flags);
    }

    private static boolean isLikelyText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return name.equals("Dockerfile") || name.equals("Makefile") || name.equals("LICENSE");
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
    }
}
