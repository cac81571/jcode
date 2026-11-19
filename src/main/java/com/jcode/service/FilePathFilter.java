package com.jcode.service;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Include / exclude globs split by files and directories.
 * Patterns are comma- or semicolon-separated. A bare {@code *.java} or {@code src}
 * matches in any directory.
 */
public final class FilePathFilter {

    private final List<PathMatcher> includeFiles;
    private final List<PathMatcher> includeDirs;
    private final List<String> includeDirNames;
    private final List<PathMatcher> excludeFiles;
    private final List<PathMatcher> excludeDirs;
    private final List<String> excludeDirNames;

    private FilePathFilter(
            List<PathMatcher> includeFiles,
            List<PathMatcher> includeDirs,
            List<String> includeDirNames,
            List<PathMatcher> excludeFiles,
            List<PathMatcher> excludeDirs,
            List<String> excludeDirNames
    ) {
        this.includeFiles = includeFiles;
        this.includeDirs = includeDirs;
        this.includeDirNames = includeDirNames;
        this.excludeFiles = excludeFiles;
        this.excludeDirs = excludeDirs;
        this.excludeDirNames = excludeDirNames;
    }

    public static FilePathFilter parse(
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs
    ) {
        List<String> includeNames = new ArrayList<>();
        List<String> excludeNames = new ArrayList<>();
        return new FilePathFilter(
                parseGlobs(includeFiles, null),
                parseGlobs(includeDirs, includeNames),
                includeNames,
                parseGlobs(excludeFiles, null),
                parseGlobs(excludeDirs, excludeNames),
                excludeNames
        );
    }

    public boolean hasFileIncludes() {
        return !includeFiles.isEmpty();
    }

    public boolean accept(Path root, Path file) {
        String relative = relativize(root, file);
        if (matchesAny(excludeFiles, relative) || underMatchingDir(relative, excludeDirs, excludeDirNames)) {
            return false;
        }
        if (!includeFiles.isEmpty() && !matchesAny(includeFiles, relative)) {
            return false;
        }
        return includeDirs.isEmpty() || underMatchingDir(relative, includeDirs, includeDirNames);
    }

    /**
     * Skip a directory subtree when that directory matches an exclude-dir rule.
     * Include-dir rules do not skip here, because a child folder may still match.
     */
    public boolean skipDirectory(Path root, Path dir) {
        if (root == null || dir == null) {
            return false;
        }
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteDir = dir.toAbsolutePath().normalize();
        if (absoluteDir.equals(absoluteRoot)) {
            return false;
        }
        String name = dir.getFileName() != null
                ? dir.getFileName().toString().toLowerCase(Locale.ROOT)
                : "";
        if (excludeDirNames.contains(name)) {
            return true;
        }
        return matchesAny(excludeDirs, relativize(root, dir));
    }

    private static boolean underMatchingDir(String relativeFile, List<PathMatcher> matchers, List<String> names) {
        if ((matchers.isEmpty() && names.isEmpty()) || relativeFile == null) {
            return false;
        }
        int slash = relativeFile.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String dir = relativeFile.substring(0, slash);
        while (!dir.isEmpty()) {
            if (matchesAny(matchers, dir)) {
                return true;
            }
            int split = dir.lastIndexOf('/');
            String name = (split < 0 ? dir : dir.substring(split + 1)).toLowerCase(Locale.ROOT);
            if (names.contains(name)) {
                return true;
            }
            if (split < 0) {
                break;
            }
            dir = dir.substring(0, split);
        }
        return false;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, String relative) {
        if (matchers.isEmpty() || relative == null || relative.isEmpty()) {
            return false;
        }
        Path path;
        try {
            String[] parts = relative.replace('\\', '/').split("/");
            path = Path.of("", parts);
        } catch (InvalidPathException ex) {
            return false;
        }
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> parseGlobs(String text, List<String> dirNamesOut) {
        List<PathMatcher> matchers = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return matchers;
        }
        for (String part : text.split("[,;]")) {
            String raw = part.trim();
            if (raw.isEmpty()) {
                continue;
            }
            if (dirNamesOut != null) {
                collectDirName(raw, dirNamesOut);
            }
            addGlob(matchers, normalizeGlob(raw));
        }
        return matchers;
    }

    private static void addGlob(List<PathMatcher> matchers, String glob) {
        try {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (glob.endsWith("/**")) {
            String base = glob.substring(0, glob.length() - 3);
            if (!base.isEmpty()) {
                try {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + base));
                } catch (IllegalArgumentException ignored) {
                    // the broader pattern is already registered
                }
            }
        }
    }

    /** Extract simple directory names from patterns like {@code test} or {@code star-star/test/star-star}. */
    private static void collectDirName(String raw, List<String> dirNamesOut) {
        String g = raw.replace('\\', '/');
        while (g.startsWith("**/")) {
            g = g.substring(3);
        }
        while (g.endsWith("/**")) {
            g = g.substring(0, g.length() - 3);
        }
        if (g.endsWith("/")) {
            g = g.substring(0, g.length() - 1);
        }
        if (!g.isEmpty() && !g.contains("/") && !g.contains("*") && !g.contains("?")) {
            dirNamesOut.add(g.toLowerCase(Locale.ROOT));
        }
    }

    private static String normalizeGlob(String raw) {
        String g = raw.replace('\\', '/');
        if (g.startsWith("./")) {
            g = g.substring(2);
        }
        if (!g.contains("/")) {
            return "**/" + g;
        }
        return g;
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return file.getFileName() != null ? file.getFileName().toString() : file.toString();
        }
    }
}
