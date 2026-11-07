package com.jcode.ui;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Ctrl+C → file name, Ctrl+Shift+C → absolute path (Explorer / Search results).
 */
public final class ClipboardPaths {

    private ClipboardPaths() {
    }

    public static void install(JComponent component, Supplier<Path> selectedPath) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyFileName");
        component.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "copyPath");
        component.getActionMap().put("copyFileName", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyFileName(selectedPath.get());
            }
        });
        component.getActionMap().put("copyPath", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyAbsolutePath(selectedPath.get());
            }
        });
    }

    public static void copyFileName(Path path) {
        if (path == null) {
            return;
        }
        Path name = path.getFileName();
        copyText(name != null ? name.toString() : path.toString());
    }

    public static void copyAbsolutePath(Path path) {
        if (path == null) {
            return;
        }
        copyText(path.toAbsolutePath().normalize().toString());
    }

    private static void copyText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }
}
