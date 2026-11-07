package com.jcode.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * FlatLaf light/dark switch with preference persistence.
 */
public final class ThemeManager {

    public enum Mode {
        DARK, LIGHT
    }

    private static final Preferences PREFS = Preferences.userNodeForPackage(ThemeManager.class);
    private static final String KEY_MODE = "theme.mode";

    private static Mode mode = load();
    private static final List<Runnable> listeners = new ArrayList<>();

    private ThemeManager() {
    }

    public static Mode mode() {
        return mode;
    }

    public static boolean isDark() {
        return mode == Mode.DARK;
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void applyStartup() {
        applyLookAndFeel(mode);
        installUiDefaults();
    }

    public static void setMode(Mode next) {
        if (next == null || next == mode) {
            return;
        }
        mode = next;
        PREFS.put(KEY_MODE, mode.name());
        applyLookAndFeel(mode);
        installUiDefaults();
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }

    public static void toggle() {
        setMode(isDark() ? Mode.LIGHT : Mode.DARK);
    }

    private static Mode load() {
        String value = PREFS.get(KEY_MODE, Mode.DARK.name());
        try {
            return Mode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Mode.DARK;
        }
    }

    private static void applyLookAndFeel(Mode mode) {
        if (mode == Mode.LIGHT) {
            FlatLightLaf.setup();
        } else {
            FlatDarkLaf.setup();
        }
    }

    private static void installUiDefaults() {
        UIManager.put("defaultFont", UiFonts.ui(13f));
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("TabbedPane.showTabSeparators", true);
    }
}
