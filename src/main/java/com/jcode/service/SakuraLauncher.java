package com.jcode.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Launches サクラエディタ with a file. Remembers a custom exe path in Preferences.
 */
public final class SakuraLauncher {

    private static final Preferences PREFS = Preferences.userNodeForPackage(SakuraLauncher.class);
    private static final String KEY_EXE = "sakura.exe";

    private SakuraLauncher() {
    }

    public static String configuredPath() {
        return PREFS.get(KEY_EXE, "");
    }

    public static void setConfiguredPath(String path) {
        if (path == null || path.isBlank()) {
            PREFS.remove(KEY_EXE);
        } else {
            PREFS.put(KEY_EXE, path.trim());
        }
    }

    public static Path resolveExecutable() {
        String configured = configuredPath();
        if (!configured.isBlank()) {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        for (Path candidate : defaultCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    public static void open(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("ファイルが見つかりません: " + file);
        }
        Path exe = resolveExecutable();
        if (exe == null) {
            throw new IOException("sakura.exe が見つかりません。File → サクラエディタのパスを設定… から指定してください。");
        }
        new ProcessBuilder(exe.toString(), file.toAbsolutePath().normalize().toString())
                .start();
    }

    private static List<Path> defaultCandidates() {
        List<Path> paths = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String local = System.getenv("LOCALAPPDATA");
        if (pf86 != null) {
            paths.add(Path.of(pf86, "sakura", "sakura.exe"));
        }
        if (pf != null) {
            paths.add(Path.of(pf, "sakura", "sakura.exe"));
        }
        if (local != null) {
            paths.add(Path.of(local, "Programs", "sakura", "sakura.exe"));
        }
        return paths;
    }
}
