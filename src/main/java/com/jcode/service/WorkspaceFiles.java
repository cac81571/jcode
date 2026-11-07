package com.jcode.service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lists workspace files for Quick Open (Ctrl+P), skipping common heavy directories.
 */
public final class WorkspaceFiles {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", ".vscode", "out", "dist", ".cursor"
    );

    private static final int MAX_FILES = 20_000;

    private WorkspaceFiles() {
    }

    public static List<Path> listFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return files;
        }
        Path absolute = root.toAbsolutePath().normalize();
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(absolute) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(file);
                return files.size() >= MAX_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Partial path match: every whitespace-separated token must appear in the relative path
     * (case-insensitive). Results are scored so filename hits rank higher.
     */
    public static List<Hit> filter(Path root, List<Path> files, String query, int limit) {
        String q = query == null ? "" : query.trim();
        List<Hit> hits = new ArrayList<>();
        for (Path file : files) {
            String relative = relativize(root, file);
            int score = score(relative, q);
            if (score >= 0) {
                hits.add(new Hit(file, relative, score));
            }
        }
        hits.sort(Comparator
                .comparingInt(Hit::score)
                .thenComparing(h -> h.relative().toLowerCase(Locale.ROOT)));
        if (hits.size() > limit) {
            return List.copyOf(hits.subList(0, limit));
        }
        return hits;
    }

    private static int score(String relative, String query) {
        String path = relative.toLowerCase(Locale.ROOT).replace('\\', '/');
        String name = fileName(path).toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return 1_000 + Math.min(path.length(), 999);
        }
        String q = query.toLowerCase(Locale.ROOT).replace('\\', '/');
        String[] tokens = q.split("\\s+");
        for (String token : tokens) {
            if (!path.contains(token)) {
                return -1;
            }
        }
        String last = tokens[tokens.length - 1];
        int score;
        if (name.startsWith(last)) {
            score = 0;
        } else if (name.contains(last)) {
            score = 100;
        } else if (path.contains("/" + last)) {
            score = 200;
        } else {
            score = 300;
        }
        score += Math.min(path.length(), 99);
        return score;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public static String relativize(Path root, Path file) {
        if (root == null) {
            return file.toString().replace('\\', '/');
        }
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return file.toString().replace('\\', '/');
        }
    }

    public record Hit(Path file, String relative, int score) {
        public String fileName() {
            int slash = relative.lastIndexOf('/');
            return slash < 0 ? relative : relative.substring(slash + 1);
        }

        public String directory() {
            int slash = relative.lastIndexOf('/');
            return slash < 0 ? "" : relative.substring(0, slash);
        }
    }
}
