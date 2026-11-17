package com.jcode.ui.git;

import com.jcode.ui.ThemeManager;

import java.awt.Color;

/**
 * Stable palette for HISTORY lanes / branch labels.
 */
final class BranchColors {

    private static final Color[] LIGHT = {
            new Color(20, 110, 190),
            new Color(0, 140, 90),
            new Color(180, 90, 20),
            new Color(140, 50, 160),
            new Color(180, 40, 70),
            new Color(30, 130, 140),
            new Color(100, 110, 20),
            new Color(70, 80, 180)
    };

    private static final Color[] DARK = {
            new Color(120, 190, 255),
            new Color(110, 220, 160),
            new Color(255, 180, 100),
            new Color(210, 150, 255),
            new Color(255, 130, 160),
            new Color(100, 210, 220),
            new Color(200, 210, 100),
            new Color(150, 160, 255)
    };

    private BranchColors() {
    }

    static Color forLane(int lane) {
        Color[] palette = ThemeManager.isDark() ? DARK : LIGHT;
        int index = Math.floorMod(lane, palette.length);
        return palette[index];
    }

    static Color forBranch(String name) {
        if (name == null || name.isBlank()) {
            return forLane(0);
        }
        return forLane(stableHash(name));
    }

    private static int stableHash(String name) {
        int h = 0;
        for (int i = 0; i < name.length(); i++) {
            h = 31 * h + name.charAt(i);
        }
        return Math.floorMod(h, LIGHT.length);
    }

    static String html(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
