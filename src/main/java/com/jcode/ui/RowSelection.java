package com.jcode.ui;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Color;

/** Light-blue row selection used by the explorer tree, outline, and file history. */
public final class RowSelection {

    public static final Color BACKGROUND = new Color(0xB3, 0xE5, 0xFC);
    public static final Color FOREGROUND = new Color(0x10, 0x2A, 0x3A);

    private static final String STYLE =
            "selectionBackground: #B3E5FC; selectionForeground: #102A3A; "
                    + "selectionInactiveBackground: #B3E5FC; selectionInactiveForeground: #102A3A";

    private RowSelection() {
    }

    public static void install(JComponent component) {
        apply(component);
        ThemeManager.addListener(() -> apply(component));
    }

    private static void apply(JComponent component) {
        component.putClientProperty("FlatLaf.style", STYLE);
        if (component instanceof JList<?> list) {
            list.setSelectionBackground(BACKGROUND);
            list.setSelectionForeground(FOREGROUND);
        } else if (component instanceof JTable table) {
            table.setSelectionBackground(BACKGROUND);
            table.setSelectionForeground(FOREGROUND);
        } else if (component instanceof JTree tree
                && tree.getCellRenderer() instanceof DefaultTreeCellRenderer renderer) {
            renderer.setBackgroundSelectionColor(BACKGROUND);
            renderer.setTextSelectionColor(FOREGROUND);
            renderer.setBorderSelectionColor(BACKGROUND);
        }
    }
}
