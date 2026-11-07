package com.jcode.service;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens a file or folder with the OS default application (external editor / explorer).
 */
public final class ExternalOpener {

    private ExternalOpener() {
    }

    public static void open(Path path) throws IOException {
        if (path == null) {
            throw new IOException("パスが指定されていません");
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IOException("見つかりません: " + absolute);
        }
        File file = absolute.toFile();
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file);
                return;
            }
        }
        openWithOsCommand(file);
    }

    private static void openWithOsCommand(File file) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder builder;
        if (os.contains("win")) {
            builder = new ProcessBuilder("cmd", "/c", "start", "", file.getAbsolutePath());
        } else if (os.contains("mac")) {
            builder = new ProcessBuilder("open", file.getAbsolutePath());
        } else {
            builder = new ProcessBuilder("xdg-open", file.getAbsolutePath());
        }
        builder.start();
    }
}
