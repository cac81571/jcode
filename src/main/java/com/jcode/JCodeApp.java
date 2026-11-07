package com.jcode;

import com.jcode.ui.MainFrame;
import com.jcode.ui.ThemeManager;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

/**
 * jcode — VSCode-inspired lightweight IDE (Swing + FlatLaf + JGit).
 */
public final class JCodeApp {

    private JCodeApp() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        ThemeManager.applyStartup();

        Path openPath = args.length > 0 ? Path.of(args[0]).toAbsolutePath().normalize() : null;

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (openPath != null) {
                frame.openWorkspace(openPath);
            } else {
                frame.restoreSessionIfNeeded();
            }
        });
    }
}
