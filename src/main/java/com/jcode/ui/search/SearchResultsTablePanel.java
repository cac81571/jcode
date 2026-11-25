package com.jcode.ui.search;

import com.jcode.model.SearchMatch;
import com.jcode.service.SearchReportBuilder;
import com.jcode.service.SearchReportBuilder.TableRow;
import com.jcode.ui.ClipboardPaths;
import com.jcode.ui.ThemeManager;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;

/**
 * Search hits as a table: No / ファイル / 行数 / ヒット / ヒット文字列 / テキスト.
 * Includes ±N context lines around each hit.
 */
public final class SearchResultsTablePanel extends JPanel {

    private static final Color TABLE_BACKGROUND = Color.WHITE;
    private static final Color TABLE_FOREGROUND = new Color(30, 30, 30);

    /** Opens the file for a double-clicked row. */
    @FunctionalInterface
    public interface RowOpener {
        void open(TableRow row, String query, boolean matchCase, boolean wholeWord, boolean regex);
    }

    private final ResultsModel model = new ResultsModel();
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);
    private RowOpener onOpenRow = (row, query, matchCase, wholeWord, regex) -> {
    };
    private String query = "";
    private boolean matchCase;
    private boolean wholeWord;
    private boolean regex;

    public SearchResultsTablePanel() {
        super(new BorderLayout());
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowSelectionAllowed(true);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);

        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(0).setPreferredWidth(56);
        columns.getColumn(0).setMaxWidth(72);
        columns.getColumn(1).setPreferredWidth(260);
        columns.getColumn(2).setPreferredWidth(64);
        columns.getColumn(2).setMaxWidth(80);
        columns.getColumn(3).setPreferredWidth(56);
        columns.getColumn(3).setMaxWidth(72);
        columns.getColumn(4).setPreferredWidth(160);
        columns.getColumn(5).setPreferredWidth(480);

        LineRenderer cellRenderer = new LineRenderer(model, false);
        table.setDefaultRenderer(Object.class, cellRenderer);
        table.setDefaultRenderer(Integer.class, cellRenderer);
        table.getColumnModel().getColumn(5).setCellRenderer(new LineRenderer(model, true));
        ClipboardPaths.install(table, () -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                return null;
            }
            TableRow line = model.rowAt(table.convertRowIndexToModel(row));
            return line == null ? null : line.file();
        });
        installCopyKeys();
        applyWhiteBackground();
        ThemeManager.addListener(() -> {
            installCopyKeys();
            applyWhiteBackground();
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showCopyMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showCopyMenu(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openRowAt(e);
                }
            }
        });

        JButton copy = new JButton("TSV形式でコピー");
        copy.setToolTipText("表全体をタブ区切りでコピーします");
        table.setToolTipText("ダブルクリックでファイルを開く。Ctrl+A で全選択、Ctrl+C で選択行を TSV コピー");
        copy.setFocusable(false);
        copy.addActionListener(e -> copyForExcel());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bar.add(copy);

        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    private void applyWhiteBackground() {
        table.setBackground(TABLE_BACKGROUND);
        table.setForeground(TABLE_FOREGROUND);
        table.getTableHeader().setBackground(TABLE_BACKGROUND);
        table.getTableHeader().setForeground(TABLE_FOREGROUND);
        scroll.setBackground(TABLE_BACKGROUND);
        scroll.getViewport().setBackground(TABLE_BACKGROUND);
    }

    private void installCopyKeys() {
        table.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAll");
        table.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyTableForExcel");
        table.getActionMap().put("copyTableForExcel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelection();
            }
        });
    }

    private void showCopyMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyTable = new JMenuItem("TSV形式でコピー");
        copyTable.addActionListener(ev -> copyForExcel());
        menu.add(copyTable);
        TableRow line = row < 0 ? null : model.rowAt(table.convertRowIndexToModel(row));
        if (line != null && line.file() != null) {
            JMenuItem copyPath = new JMenuItem("パスをコピー");
            copyPath.addActionListener(ev -> ClipboardPaths.copyAbsolutePath(line.file()));
            menu.add(copyPath);
        }
        menu.show(table, e.getX(), e.getY());
    }

    /** Tab-separated, with a header row, so a spreadsheet pastes one cell per column. */
    private void copyForExcel() {
        copyRows(null);
    }

    /** Copies the selected rows. With no selection, copies the whole table. */
    private void copySelection() {
        int[] selected = table.getSelectedRows();
        copyRows(selected.length == 0 ? null : selected);
    }

    private void copyRows(int[] viewRows) {
        StringBuilder out = new StringBuilder();
        int cols = model.getColumnCount();
        for (int col = 0; col < cols; col++) {
            if (col > 0) {
                out.append('\t');
            }
            out.append(excelCell(model.getColumnName(col)));
        }
        int count = viewRows == null ? model.getRowCount() : viewRows.length;
        for (int i = 0; i < count; i++) {
            int row = viewRows == null ? i : table.convertRowIndexToModel(viewRows[i]);
            out.append("\r\n");
            for (int col = 0; col < cols; col++) {
                if (col > 0) {
                    out.append('\t');
                }
                out.append(excelCell(model.getValueAt(row, col)));
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(out.toString()), null);
    }

    private static String excelCell(Object value) {
        String text = value == null ? "" : value.toString();
        text = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (text.indexOf('\t') >= 0 || text.indexOf('"') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public void setOnOpenRow(RowOpener onOpenRow) {
        this.onOpenRow = onOpenRow != null ? onOpenRow : (row, query, matchCase, wholeWord, regex) -> {
        };
    }

    public void setSearchContext(String query, boolean matchCase, boolean wholeWord, boolean regex) {
        this.query = query == null ? "" : query;
        this.matchCase = matchCase;
        this.wholeWord = wholeWord;
        this.regex = regex;
    }

    public void setMatches(List<SearchMatch> matches, Path workspaceRoot, int contextLines) {
        model.setRows(SearchReportBuilder.tableRows(matches, contextLines), workspaceRoot);
        table.clearSelection();
    }

    private void openRowAt(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        TableRow line = model.rowAt(table.convertRowIndexToModel(row));
        if (line == null || line.file() == null) {
            return;
        }
        onOpenRow.open(line, query, matchCase, wholeWord, regex);
    }

    private static String compact(String line) {
        if (line == null) {
            return "";
        }
        return line.replace('\t', ' ').strip();
    }

    /** Joined matched substrings for a row; empty for context-only lines. */
    private static String hitText(TableRow line) {
        if (line == null || line.text() == null || line.spans().length == 0) {
            return "";
        }
        String text = line.text();
        StringBuilder out = new StringBuilder();
        for (int[] span : line.spans()) {
            int start = Math.max(0, Math.min(span[0], text.length()));
            int end = Math.max(start, Math.min(span[1], text.length()));
            if (start >= end) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(text, start, end);
        }
        return out.toString();
    }

    /**
     * Paints the matched substring in the text column. Other columns stay plain.
     */
    private static final class LineRenderer extends DefaultTableCellRenderer {
        private static final Color MARK = new Color(255, 210, 120);
        private static final Color MARK_TEXT = new Color(40, 28, 8);

        private final ResultsModel model;
        private final boolean paintHits;
        private String plain = "";
        private int[][] spans = new int[0][];

        LineRenderer(ResultsModel model, boolean paintHits) {
            this.model = model;
            this.paintHits = paintHits;
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            TableRow line = model.rowAt(table.convertRowIndexToModel(row));
            if (line != null) {
                setToolTipText((line.hit() ? "ヒット  " : "前後  ")
                        + line.file().getFileName() + ":" + line.lineNumber()
                        + "  " + compact(line.text()));
            } else {
                setToolTipText(null);
            }
            setHorizontalAlignment(column == 0 || column == 2 || column == 3
                    ? SwingConstants.CENTER
                    : SwingConstants.LEFT);
            if (!isSelected) {
                setBackground(TABLE_BACKGROUND);
                setForeground(TABLE_FOREGROUND);
            }
            if (paintHits) {
                plain = line == null || line.text() == null ? "" : line.text();
                spans = line == null ? new int[0][] : line.spans();
                setText(spans.length > 0 ? null : plain);
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!paintHits || spans.length == 0 || plain.isEmpty()) {
                super.paintComponent(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                applyTextHints(g2);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setFont(getFont());
                FontMetrics metrics = g2.getFontMetrics();
                Insets insets = getInsets();
                int y = insets.top + ((getHeight() - insets.top - insets.bottom) - metrics.getHeight()) / 2
                        + metrics.getAscent();
                int x = insets.left;
                int cursor = 0;
                Color textColor = getForeground();
                for (int[] span : spans) {
                    int start = Math.max(cursor, Math.min(span[0], plain.length()));
                    int end = Math.max(start, Math.min(span[1], plain.length()));
                    x = drawSegment(g2, metrics, plain.substring(cursor, start), x, y, textColor, false);
                    x = drawSegment(g2, metrics, plain.substring(start, end), x, y, MARK_TEXT, true);
                    cursor = end;
                }
                drawSegment(g2, metrics, plain.substring(cursor), x, y, textColor, false);
            } finally {
                g2.dispose();
            }
        }

        /** Swing labels use the desktop text hints. Direct drawString does not. */
        private static void applyTextHints(Graphics2D g2) {
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof java.util.Map<?, ?> map) {
                g2.addRenderingHints(map);
            } else {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
        }

        private static int drawSegment(
                Graphics g, FontMetrics metrics, String text, int x, int y, Color color, boolean mark
        ) {
            if (text.isEmpty()) {
                return x;
            }
            int width = metrics.stringWidth(text);
            if (mark) {
                g.setColor(ThemeManager.isDark() ? new Color(255, 196, 90) : MARK);
                g.fillRect(x, y - metrics.getAscent(), width, metrics.getHeight());
            }
            g.setColor(color);
            g.drawString(text, x, y);
            return x + width;
        }
    }

    private static final class ResultsModel extends AbstractTableModel {
        private final String[] columns = {"No", "ファイル", "行数", "ヒット", "ヒット文字列", "テキスト"};
        private List<TableRow> rows = List.of();
        private Path root;

        void setRows(List<TableRow> rows, Path root) {
            this.rows = rows == null ? List.of() : List.copyOf(rows);
            this.root = root;
            fireTableDataChanged();
        }

        TableRow rowAt(int row) {
            if (row < 0 || row >= rows.size()) {
                return null;
            }
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 || columnIndex == 2 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            TableRow line = rows.get(rowIndex);
            String path = line.file() == null ? "" : display(line.file());
            return switch (columnIndex) {
                case 0 -> rowIndex + 1;
                case 1 -> path;
                case 2 -> line.lineNumber();
                case 3 -> line.hit() ? "●" : "";
                case 4 -> hitText(line);
                case 5 -> line.text() == null ? "" : line.text();
                default -> "";
            };
        }

        private String display(Path file) {
            if (root != null && file.startsWith(root)) {
                return root.relativize(file).toString().replace('\\', '/');
            }
            return file.toString().replace('\\', '/');
        }
    }
}
