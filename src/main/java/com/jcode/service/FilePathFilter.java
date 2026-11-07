package com.jcode.service;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VS Code-style include / exclude globs for workspace search.
 * Patterns are comma- or semicolon-separated; a bare {@code *.java} means any-directory match.
 */
public final class FilePathFilter {

    private final List<PathMatcher> includes;
    private final List<PathMatcher> excludes;
    private final List<String> excludeDirNames;

    private FilePathFilter(
            List<PathMatcher> includes,
            List<PathMatcher> excludes,
            List<String> excludeDirNames
    ) {
        this.includes = includes;
        this.excludes = excludes;
        this.excludeDirNames = excludeDirNames;
    }

    public static FilePathFilter parse(String includeText, String excludeText) {
        List<String> dirNames = new ArrayList<>();
        List<PathMatcher> excludeMatchers = parseGlobs(excludeText, dirNames);
        return new FilePathFilter(parseGlobs(includeText, null), excludeMatchers, dirNames);
    }

    public boolean hasIncludes() {
        return !includes.isEmpty();
    }

    public boolean accept(Path root, Path file) {
        String relative = relativize(root, file);
        if (matchesAny(excludes, relative)) {
            return false;
        }
        if (includes.isEmpty()) {
            return true;
        }
        return matchesAny(includes, relative);
    }

    /**
     * Skip a directory subtree when its name or relative path matches exclude rules.
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
        String relative = relativize(root, dir);
        return matchesAny(excludes, relative);
    }

    private static boolean matchesAny(List<PathMatcher> matchers, String relative) {
        if (matchers.isEmpty() || relative == null || relative.isEmpty()) {
            return false;
        }
        Path path;
        try {
            // Build from segments so '*' never appears as a path char (Windows)
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
            String glob = normalizeGlob(raw);
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            } catch (IllegalArgumentException ignored) {
                // skip invalid pattern (includes InvalidPathException)
            }
        }
        return matchers;
    }

    /** Extract simple directory names from patterns like test, star-star/test, star-star/test/star-star. */
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
        // "*.java" → "**/*.java" (any directory), same as VS Code
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
