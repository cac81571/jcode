package com.jcode.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.Set;

/**
 * Picks fonts that can render Japanese (and other CJK) glyphs.
 * Cascadia Code / Consolas look nice for Latin but show tofu (□) for 日本語.
 */
public final class UiFonts {

    private static final String[] UI_PREFERRED = {
            "Yu Gothic UI",
            "Yu Gothic",
            "Meiryo UI",
            "Meiryo",
            "MS UI Gothic",
            "Hiragino Sans",
            "Noto Sans CJK JP",
            "Segoe UI",
            Font.DIALOG
    };

    /** Prefer CJK-capable monospace first; Latin-only fonts are last resort. */
    private static final String[] MONO_PREFERRED = {
            "MS Gothic",
            "MS Mincho",
            "Yu Gothic",
            "Yu Gothic UI",
            "Noto Sans Mono CJK JP",
            "Hiragino Sans",
            "Meiryo",
            "Cascadia Code",
            "Consolas",
            Font.MONOSPACED
    };

    private UiFonts() {
    }

    public static Font ui(float size) {
        return pick(UI_PREFERRED, size, true);
    }

    public static Font mono(float size) {
        Font withCjk = pick(MONO_PREFERRED, size, true);
        if (withCjk != null) {
            return withCjk;
        }
        return pick(MONO_PREFERRED, size, false);
    }

    private static Font pick(String[] candidates, float size, boolean requireJapanese) {
        Set<String> available = Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)
        );
        for (String name : candidates) {
            boolean logical = Font.DIALOG.equals(name) || Font.MONOSPACED.equals(name);
            if (!logical && !containsIgnoreCase(available, name)) {
                continue;
            }
            Font font = new Font(name, Font.PLAIN, Math.round(size));
            if (requireJapanese && !canDisplayJapanese(font)) {
                continue;
            }
            return font.deriveFont(size);
        }
        return requireJapanese
                ? null
                : new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size)).deriveFont(size);
    }

    private static boolean canDisplayJapanese(Font font) {
        return font.canDisplay('あ') && font.canDisplay('開') && font.canDisplay('取');
    }

    private static boolean containsIgnoreCase(Set<String> names, String target) {
        for (String name : names) {
            if (name.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }
}
