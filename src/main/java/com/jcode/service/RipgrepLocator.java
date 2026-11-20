package com.jcode.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Finds the ripgrep executable.
 * Order: directory of the jcode jar ({@code rg.exe} on Windows, {@code rg} elsewhere),
 * the process working directory (so {@code run.bat} can keep {@code rg.exe} beside it),
 * the configured path, then {@code PATH}.
 */
public final class RipgrepLocator {

    private static final Preferences PREFS = Preferences.userNodeForPackage(RipgrepLocator.class);
    private static final String KEY_PATH = "rg.path";

    private RipgrepLocator() {
    }

    public static boolean isWindowsOs() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    public static String configuredPath() {
        return PREFS.get(KEY_PATH, "");
    }

    public static void setConfiguredPath(String path) {
        if (path == null || path.isBlank()) {
            PREFS.remove(KEY_PATH);
        } else {
            PREFS.put(KEY_PATH, path.trim());
        }
    }

    /**
     * Directory that contains the running jar, or null when launched from
     * {@code target/classes} (Maven / IDE) rather than a jar.
     */
    public static Path jarDirectory() {
        try {
            var source = RipgrepLocator.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            if (Files.isRegularFile(path)) {
                Path parent = path.getParent();
                return parent == null ? null : parent.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /** @return the ripgrep binary to execute, or null when none is available */
    public static Path resolve() {
        boolean windows = isWindowsOs();
        List<String> jarNames = windows ? List.of("rg.exe") : List.of("rg");
        List<String> pathNames = windows ? List.of("rg.exe", "rg") : List.of("rg");
        Path workingDirectory = null;
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            workingDirectory = Path.of(userDir);
        }
        return resolve(new Lookup(
                jarDirectory(), workingDirectory, configuredPath(), System.getenv("PATH"),
                windows, jarNames, pathNames));
    }

    record Lookup(
            Path jarDirectory,
            Path workingDirectory,
            String configured,
            String pathEnv,
            boolean windows,
            List<String> jarNames,
            List<String> pathNames
    ) {
    }

    static Path resolve(Lookup lookup) {
        if (lookup.jarDirectory != null) {
            Path besideJar = firstRunnable(lookup.jarDirectory, lookup.jarNames, lookup.windows);
            if (besideJar != null) {
                return besideJar;
            }
        }
        if (lookup.workingDirectory != null && !lookup.workingDirectory.equals(lookup.jarDirectory)) {
            Path besideWork = firstRunnable(lookup.workingDirectory, lookup.jarNames, lookup.windows);
            if (besideWork != null) {
                return besideWork;
            }
        }
        if (lookup.configured != null && !lookup.configured.isBlank()) {
            try {
                Path configured = Path.of(lookup.configured.trim());
                if (isRunnable(configured, lookup.windows)) {
                    return configured.toAbsolutePath().normalize();
                }
            } catch (Exception ignored) {
                // fall through to PATH
            }
        }
        return searchPath(lookup.pathEnv, lookup.pathNames, lookup.windows);
    }

    private static Path searchPath(String pathEnv, List<String> names, boolean windows) {
        if (pathEnv == null || pathEnv.isBlank() || names == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path found = firstRunnable(Path.of(dir), names, windows);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Path firstRunnable(Path dir, List<String> names, boolean windows) {
        if (dir == null || names == null) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        for (String name : names) {
            candidates.add(dir.resolve(name));
        }
        for (Path candidate : candidates) {
            if (isRunnable(candidate, windows)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * On Windows, {@code Files.isExecutable} is unreliable for {@code .exe} files
     * copied without a Unix permission bit, so a regular file is enough.
     */
    static boolean isRunnable(Path path, boolean windows) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        return windows || Files.isExecutable(path);
    }
}
