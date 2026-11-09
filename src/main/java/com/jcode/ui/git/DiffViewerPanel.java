package com.jcode.ui.git;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import com.jcode.model.DiffTexts;
import com.jcode.ui.ThemeManager;
import com.jcode.ui.UiFonts;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SmartHighlightPainter;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Colored diff viewer (side-by-side / unified) powered by java-diff-utils.
 */
public final class DiffViewerPanel extends JPanel {

    public enum Mode {
        SIDE_BY_SIDE, UNIFIED
    }

    /** Private-use markers for DiffRowGenerator inline spans (stripped before display). */
    private static final char INLINE_OPEN = '\uE000';
    private static final char INLINE_CLOSE = '\uE001';

    private final JLabel title = new JLabel(" ");
    private final JRadioButton sideBySideBtn = new JRadioButton("左右分割", true);
    private final JRadioButton unifiedBtn = new JRadioButton("Unified");
    private final JSpinner contextSpinner = new JSpinner(new SpinnerNumberModel(3, 0, 20, 1));
    private final JPanel body = new JPanel(new BorderLayout());

    private final RSyntaxTextArea leftArea = createArea();
    private final RSyntaxTextArea rightArea = createArea();
    private final RSyntaxTextArea unifiedArea = createArea();
    private final RTextScrollPane leftScroll = new RTextScrollPane(leftArea);
    private final RTextScrollPane rightScroll = new RTextScrollPane(rightArea);
    private final RTextScrollPane unifiedScroll = new RTextScrollPane(unifiedArea);
    private final JSplitPane sideSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);

    private final List<Object> leftLineTags = new ArrayList<>();
    private final List<Object> rightLineTags = new ArrayList<>();
    private final List<Object> unifiedLineTags = new ArrayList<>();
    private final List<Object> leftInlineTags = new ArrayList<>();
    private final List<Object> rightInlineTags = new ArrayList<>();
    private final List<Object> unifiedInlineTags = new ArrayList<>();

    private DiffTexts current;
    private Mode mode = Mode.SIDE_BY_SIDE;

    public DiffViewerPanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        ButtonGroup group = new ButtonGroup();
        group.add(sideBySideBtn);
        group.add(unifiedBtn);
        sideBySideBtn.addActionListener(e -> setMode(Mode.SIDE_BY_SIDE));
        unifiedBtn.addActionListener(e -> setMode(Mode.UNIFIED));
        contextSpinner.setToolTipText("変更箇所の前後に残す行数");
        ((JSpinner.DefaultEditor) contextSpinner.getEditor()).getTextField().setColumns(2);
        contextSpinner.addChangeListener(e -> {
            if (current != null) {
                showDiff(current);
            }
        });

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(title, BorderLayout.WEST);
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        modes.add(new JLabel("前後"));
        modes.add(contextSpinner);
        modes.add(new JLabel("行"));
        modes.add(sideBySideBtn);
        modes.add(unifiedBtn);
        toolbar.add(modes, BorderLayout.EAST);

        sideSplit.setResizeWeight(0.5);
        sideSplit.setContinuousLayout(true);
        leftScroll.setLineNumbersEnabled(true);
        rightScroll.setLineNumbersEnabled(true);
        unifiedScroll.setLineNumbersEnabled(true);

        // Keep vertical scroll in sync for side-by-side
        final boolean[] syncing = {false};
        leftScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            rightScroll.getVerticalScrollBar().setValue(e.getValue());
            syncing[0] = false;
        });
        rightScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (syncing[0]) {
                return;
            }
            syncing[0] = true;
            leftScroll.getVerticalScrollBar().setValue(e.getValue());
            syncing[0] = false;
        });

        add(toolbar, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        showEmpty();

        ThemeManager.addListener(() -> {
            if (current != null) {
                showDiff(current);
            }
        });
    }

    public void clear() {
        current = null;
        title.setText(" ");
        showEmpty();
    }

    public void showDiff(DiffTexts texts) {
        this.current = texts;
        if (texts == null) {
            clear();
            return;
        }
        title.setText(texts.path());
        if (mode == Mode.SIDE_BY_SIDE) {
            renderSideBySide(texts);
        } else {
            renderUnified(texts);
        }
    }

    private void setMode(Mode next) {
        this.mode = next;
        if (current != null) {
            showDiff(current);
        } else {
            showEmpty();
        }
    }

    private void showEmpty() {
        clearHighlights();
        leftArea.setText("");
        rightArea.setText("");
        unifiedArea.setText("(diff なし)");
        body.removeAll();
        if (mode == Mode.SIDE_BY_SIDE) {
            body.add(sideSplit, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> sideSplit.setDividerLocation(0.5));
        } else {
            body.add(unifiedScroll, BorderLayout.CENTER);
        }
        body.revalidate();
        body.repaint();
    }

    private void renderSideBySide(DiffTexts texts) {
        clearHighlights();
        List<String> oldLines = toLines(texts.oldText());
        List<String> newLines = toLines(texts.newText());

        DiffRowGenerator generator = DiffRowGenerator.create()
                .showInlineDiffs(true)
                .inlineDiffByWord(true)
                .oldTag(open -> open ? String.valueOf(INLINE_OPEN) : String.valueOf(INLINE_CLOSE))
                .newTag(open -> open ? String.valueOf(INLINE_OPEN) : String.valueOf(INLINE_CLOSE))
                .ignoreWhiteSpaces(false)
                .build();
        List<DiffRow> rows = generator.generateDiffRows(oldLines, newLines);
        int context = (Integer) contextSpinner.getValue();
        List<SideRow> visible = collapseToNearbyChanges(rows, context);

        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        List<Color> leftColors = new ArrayList<>();
        List<Color> rightColors = new ArrayList<>();
        List<int[]> leftInline = new ArrayList<>();
        List<int[]> rightInline = new ArrayList<>();
        Color delete = deleteColor();
        Color insert = insertColor();
        Color change = changeColor();
        Color gap = metaColor();

        for (SideRow item : visible) {
            if (item.separator()) {
                left.append("⋯\n");
                right.append("⋯\n");
                leftColors.add(gap);
                rightColors.add(gap);
                continue;
            }
            DiffRow row = item.row();
            switch (row.getTag()) {
                case INSERT -> {
                    left.append('\n');
                    leftColors.add(null);
                    // Whole-line color only; no orange inline on pure adds
                    appendParsedLine(row.getNewLine(), right, rightColors, null, insert);
                }
                case DELETE -> {
                    // Whole-line color only; no orange inline on pure deletes
                    appendParsedLine(row.getOldLine(), left, leftColors, null, delete);
                    right.append('\n');
                    rightColors.add(null);
                }
                case CHANGE -> {
                    appendParsedLine(row.getOldLine(), left, leftColors, leftInline, change);
                    appendParsedLine(row.getNewLine(), right, rightColors, rightInline, change);
                }
                default -> {
                    appendParsedLine(row.getOldLine(), left, leftColors, leftInline, null);
                    appendParsedLine(row.getNewLine(), right, rightColors, rightInline, null);
                }
            }
        }

        if (visible.isEmpty()) {
            leftArea.setText("(差分なし)");
            rightArea.setText("(差分なし)");
        } else {
            leftArea.setText(trimTrailingNewline(left));
            rightArea.setText(trimTrailingNewline(right));
        }
        leftArea.setCaretPosition(0);
        rightArea.setCaretPosition(0);
        applyLineColors(leftArea, leftScroll, leftColors, leftLineTags);
        applyLineColors(rightArea, rightScroll, rightColors, rightLineTags);
        applyInlineHighlights(leftArea, leftInline, leftInlineTags);
        applyInlineHighlights(rightArea, rightInline, rightInlineTags);

        body.removeAll();
        body.add(sideSplit, BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
        SwingUtilities.invokeLater(() -> sideSplit.setDividerLocation(0.5));
    }

    private static void appendParsedLine(
            String raw,
            StringBuilder out,
            List<Color> lineColors,
            List<int[]> inlineRanges,
            Color lineColor
    ) {
        int lineStart = out.length();
        ParsedLine parsed = parseInline(unescape(raw == null ? "" : raw));
        out.append(parsed.text()).append('\n');
        lineColors.add(lineColor);
        if (inlineRanges == null) {
            return;
        }
        for (int[] range : parsed.ranges()) {
            inlineRanges.add(new int[]{lineStart + range[0], lineStart + range[1]});
        }
    }

    private static ParsedLine parseInline(String raw) {
        StringBuilder clean = new StringBuilder(raw.length());
        List<int[]> ranges = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == INLINE_OPEN) {
                start = clean.length();
            } else if (c == INLINE_CLOSE) {
                if (start >= 0 && start < clean.length()) {
                    ranges.add(new int[]{start, clean.length()});
                }
                start = -1;
            } else {
                clean.append(c);
            }
        }
        return new ParsedLine(clean.toString(), ranges);
    }

    private record ParsedLine(String text, List<int[]> ranges) {
    }

    /**
     * Keep changed rows plus ±context EQUAL neighbors; insert separators between distant hunks.
     */
    private static List<SideRow> collapseToNearbyChanges(List<DiffRow> rows, int context) {
        if (rows.isEmpty()) {
            return List.of();
        }
        boolean[] keep = new boolean[rows.size()];
        boolean anyChange = false;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getTag() != DiffRow.Tag.EQUAL) {
                anyChange = true;
                int from = Math.max(0, i - context);
                int to = Math.min(rows.size() - 1, i + context);
                for (int j = from; j <= to; j++) {
                    keep[j] = true;
                }
            }
        }
        if (!anyChange) {
            return List.of();
        }

        List<SideRow> visible = new ArrayList<>();
        boolean inSkipped = false;
        for (int i = 0; i < rows.size(); i++) {
            if (keep[i]) {
                inSkipped = false;
                visible.add(SideRow.row(rows.get(i)));
            } else if (!inSkipped) {
                inSkipped = true;
                visible.add(SideRow.gap());
            }
        }
        return visible;
    }

    private record SideRow(DiffRow row, boolean separator) {
        static SideRow row(DiffRow row) {
            return new SideRow(row, false);
        }

        static SideRow gap() {
            return new SideRow(null, true);
        }
    }

    private void renderUnified(DiffTexts texts) {
        clearHighlights();
        List<String> oldLines = toLines(texts.oldText());
        List<String> newLines = toLines(texts.newText());

        DiffRowGenerator generator = DiffRowGenerator.create()
                .showInlineDiffs(true)
                .inlineDiffByWord(true)
                .oldTag(open -> open ? String.valueOf(INLINE_OPEN) : String.valueOf(INLINE_CLOSE))
                .newTag(open -> open ? String.valueOf(INLINE_OPEN) : String.valueOf(INLINE_CLOSE))
                .ignoreWhiteSpaces(false)
                .build();
        List<DiffRow> rows = generator.generateDiffRows(oldLines, newLines);
        int context = (Integer) contextSpinner.getValue();
        List<SideRow> visible = collapseToNearbyChanges(rows, context);

        StringBuilder content = new StringBuilder();
        List<Color> colors = new ArrayList<>();
        List<int[]> inline = new ArrayList<>();
        Color delete = deleteColor();
        Color insert = insertColor();
        Color gap = metaColor();

        content.append("--- a/").append(texts.path()).append('\n');
        colors.add(gap);
        content.append("+++ b/").append(texts.path()).append('\n');
        colors.add(gap);

        for (SideRow item : visible) {
            if (item.separator()) {
                content.append("⋯\n");
                colors.add(gap);
                continue;
            }
            DiffRow row = item.row();
            switch (row.getTag()) {
                case INSERT ->
                        appendUnifiedLine(row.getNewLine(), '+', content, colors, null, insert);
                case DELETE ->
                        appendUnifiedLine(row.getOldLine(), '-', content, colors, null, delete);
                case CHANGE -> {
                    // Orange only on changed spans within -/+, not on pure add/delete rows
                    appendUnifiedLine(row.getOldLine(), '-', content, colors, inline, delete);
                    appendUnifiedLine(row.getNewLine(), '+', content, colors, inline, insert);
                }
                default ->
                        appendUnifiedLine(row.getOldLine(), ' ', content, colors, null, null);
            }
        }

        if (visible.isEmpty()) {
            unifiedArea.setText("(差分なし)");
        } else {
            unifiedArea.setText(trimTrailingNewline(content));
        }
        unifiedArea.setCaretPosition(0);
        applyLineColors(unifiedArea, unifiedScroll, colors, unifiedLineTags);
        applyInlineHighlights(unifiedArea, inline, unifiedInlineTags);

        body.removeAll();
        body.add(unifiedScroll, BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
    }

    /** Append one unified line (` `, `+`, or `-` prefix). Inline ranges skip the prefix char. */
    private static void appendUnifiedLine(
            String raw,
            char prefix,
            StringBuilder out,
            List<Color> lineColors,
            List<int[]> inlineRanges,
            Color lineColor
    ) {
        int lineStart = out.length();
        ParsedLine parsed = parseInline(unescape(raw == null ? "" : raw));
        out.append(prefix).append(parsed.text()).append('\n');
        lineColors.add(lineColor);
        if (inlineRanges == null) {
            return;
        }
        int contentStart = lineStart + 1;
        for (int[] range : parsed.ranges()) {
            inlineRanges.add(new int[]{contentStart + range[0], contentStart + range[1]});
        }
    }

    private void applyLineColors(
            RSyntaxTextArea area,
            RTextScrollPane scroll,
            List<Color> colors,
            List<Object> tags
    ) {
        scroll.setLineNumbersEnabled(true);
        for (int i = 0; i < colors.size(); i++) {
            Color color = colors.get(i);
            if (color == null) {
                continue;
            }
            try {
                tags.add(area.addLineHighlight(i, color));
            } catch (BadLocationException ignored) {
                // skip
            }
        }
    }

    private void applyInlineHighlights(RSyntaxTextArea area, List<int[]> ranges, List<Object> tags) {
        Highlighter highlighter = area.getHighlighter();
        SmartHighlightPainter painter = new SmartHighlightPainter(inlineColor());
        painter.setPaintBorder(true);
        int length = area.getDocument().getLength();
        for (int[] range : ranges) {
            int start = Math.max(0, Math.min(range[0], length));
            int end = Math.max(start, Math.min(range[1], length));
            if (start >= end) {
                continue;
            }
            try {
                tags.add(highlighter.addHighlight(start, end, painter));
            } catch (BadLocationException ignored) {
                // skip
            }
        }
    }

    private void clearHighlights() {
        for (Object tag : leftLineTags) {
            leftArea.removeLineHighlight(tag);
        }
        leftLineTags.clear();
        for (Object tag : rightLineTags) {
            rightArea.removeLineHighlight(tag);
        }
        rightLineTags.clear();
        for (Object tag : unifiedLineTags) {
            unifiedArea.removeLineHighlight(tag);
        }
        unifiedLineTags.clear();
        Highlighter leftHl = leftArea.getHighlighter();
        for (Object tag : leftInlineTags) {
            leftHl.removeHighlight(tag);
        }
        leftInlineTags.clear();
        Highlighter rightHl = rightArea.getHighlighter();
        for (Object tag : rightInlineTags) {
            rightHl.removeHighlight(tag);
        }
        rightInlineTags.clear();
        Highlighter unifiedHl = unifiedArea.getHighlighter();
        for (Object tag : unifiedInlineTags) {
            unifiedHl.removeHighlight(tag);
        }
        unifiedInlineTags.clear();
    }

    private static RSyntaxTextArea createArea() {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setEditable(false);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        area.setCodeFoldingEnabled(false);
        area.setAntiAliasingEnabled(true);
        area.setFont(UiFonts.mono(13f));
        area.setTabSize(4);
        boolean dark = ThemeManager.isDark();
        if (dark) {
            area.setBackground(new Color(30, 30, 30));
            area.setForeground(new Color(220, 220, 220));
            area.setCurrentLineHighlightColor(new Color(40, 40, 40));
        } else {
            area.setBackground(Color.WHITE);
            area.setForeground(new Color(30, 30, 30));
            area.setCurrentLineHighlightColor(new Color(245, 245, 245));
        }
        return area;
    }

    private static List<String> toLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return Arrays.asList(normalized.split("\n", -1));
    }

    private static String unescape(String line) {
        if (line == null) {
            return "";
        }
        // DiffRowGenerator may HTML-escape when inline diffs are on; keep plain.
        return line.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String trimTrailingNewline(StringBuilder sb) {
        if (sb.isEmpty()) {
            return "";
        }
        if (sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static Color deleteColor() {
        return ThemeManager.isDark() ? new Color(90, 40, 40) : new Color(255, 220, 220);
    }

    private static Color insertColor() {
        return ThemeManager.isDark() ? new Color(40, 80, 50) : new Color(220, 255, 220);
    }

    private static Color changeColor() {
        return ThemeManager.isDark() ? new Color(80, 70, 30) : new Color(255, 245, 200);
    }

    private static Color metaColor() {
        return ThemeManager.isDark() ? new Color(50, 50, 70) : new Color(230, 230, 245);
    }

    private static Color inlineColor() {
        return ThemeManager.isDark() ? new Color(255, 210, 140) : new Color(255, 230, 180);
    }
}
