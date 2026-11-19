package com.jcode.ui.editor;

import com.jcode.service.JavaOutlineParser;
import com.jcode.service.JavaOutlineParser.Kind;
import com.jcode.service.JavaOutlineParser.Symbol;
import com.jcode.ui.RowSelection;
import com.jcode.ui.UiFonts;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Right-side Java outline: class table, then that class's fields and methods.
 * Selecting any row jumps to its source line.
 */
public final class JavaOutlinePanel extends JPanel {

    private enum SortMode {
        SOURCE("出現順"),
        ALPHA("アルファベット順");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final JLabel title = new JLabel("アウトライン");
    private final JLabel status = new JLabel(" ");
    private final JTextField filterField = new JTextField();
    private final JRadioButton fieldSource = new JRadioButton("出現順", true);
    private final JRadioButton fieldAlpha = new JRadioButton("アルファベット順");
    private final JRadioButton methodSource = new JRadioButton("出現順", true);
    private final JRadioButton methodAlpha = new JRadioButton("アルファベット順");
    private final ButtonGroup fieldSortGroup = new ButtonGroup();
    private final ButtonGroup methodSortGroup = new ButtonGroup();
    private final SymbolTable classes = new SymbolTable("行数", "クラス");
    private final SymbolTable fields = new SymbolTable("行数", "フィールド");
    private final SymbolTable methods = new SymbolTable("行数", "メソッド");
    private List<Symbol> types = List.of();
    private boolean suppress;
    private IntConsumer onJump = line -> {
    };

    public JavaOutlinePanel() {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(280, 200));
        setMinimumSize(new Dimension(180, 120));
        title.setFont(UiFonts.ui(13f));

        filterField.putClientProperty("JTextField.placeholderText", "フィルタ");
        filterField.setToolTipText("クラス・フィールド・メソッド名で絞り込み");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyView();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyView();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyView();
            }
        });

        classes.onSelect(symbol -> {
            if (suppress || symbol == null) {
                return;
            }
            showMembers(symbol);
            jumpLater(symbol.line());
        });
        fields.onSelect(symbol -> {
            if (!suppress && symbol != null) {
                jumpLater(symbol.line());
            }
        });
        methods.onSelect(symbol -> {
            if (!suppress && symbol != null) {
                jumpLater(symbol.line());
            }
        });
        wireSort(fieldSource, fieldAlpha, fieldSortGroup, "フィールドの並び順");
        wireSort(methodSource, methodAlpha, methodSortGroup, "メソッドの並び順");

        JSplitPane members = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                section("フィールド", sortRadios(fieldSource, fieldAlpha), fields),
                section("メソッド", sortRadios(methodSource, methodAlpha), methods));
        members.setResizeWeight(0.5);
        members.setContinuousLayout(true);
        JSplitPane body = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                section("クラス", null, classes),
                members);
        body.setResizeWeight(0.34);
        body.setContinuousLayout(true);

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(title, BorderLayout.NORTH);
        header.add(filterField, BorderLayout.SOUTH);

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    public void setOnJump(IntConsumer onJump) {
        this.onJump = onJump != null ? onJump : line -> {
        };
    }

    /** After the click finishes, so moving focus to the editor does not drop the caret move. */
    private void jumpLater(int line) {
        SwingUtilities.invokeLater(() -> onJump.accept(line));
    }

    public void showSource(String fileName, String source) {
        title.setText("アウトライン — " + (fileName == null ? "" : fileName));
        types = flattenTypes(JavaOutlineParser.parse(source));
        applyView();
    }

    public void showMessage(String message) {
        title.setText("アウトライン");
        types = List.of();
        suppress = true;
        try {
            classes.setRows(List.of());
            fields.setRows(List.of());
            methods.setRows(List.of());
        } finally {
            suppress = false;
        }
        status.setText(message == null ? " " : message);
    }

    private void applyView() {
        Symbol previous = classes.selected();
        String query = query();
        List<Symbol> shown = sort(filterTypes(types, query), SortMode.SOURCE);
        int index = indexOf(shown, previous);
        if (index < 0 && !shown.isEmpty()) {
            index = 0;
        }
        suppress = true;
        try {
            classes.setRows(shown);
            classes.select(index);
            showMembers(classes.selected());
        } finally {
            suppress = false;
        }
        if (types.isEmpty()) {
            status.setText("クラスなし");
        } else if (query.isEmpty()) {
            status.setText(types.size() + " クラス");
        } else {
            status.setText(shown.size() + " / " + types.size() + " クラス");
        }
    }

    private void showMembers(Symbol type) {
        String query = query();
        boolean classNameHit = type != null && (query.isEmpty() || contains(type.label(), query));
        List<Symbol> fieldRows = new ArrayList<>();
        List<Symbol> methodRows = new ArrayList<>();
        if (type != null) {
            for (Symbol child : type.children()) {
                boolean visible = query.isEmpty() || classNameHit || contains(child.label(), query);
                if (!visible) {
                    continue;
                }
                if (child.kind() == Kind.FIELD) {
                    fieldRows.add(child);
                } else if (child.kind() == Kind.METHOD || child.kind() == Kind.CONSTRUCTOR) {
                    methodRows.add(child);
                }
            }
        }
        suppress = true;
        try {
            fields.setRows(sort(fieldRows, selected(fieldAlpha)));
            methods.setRows(sort(methodRows, selected(methodAlpha)));
        } finally {
            suppress = false;
        }
    }

    private String query() {
        return filterField.getText() == null ? "" : filterField.getText().trim();
    }

    private void wireSort(JRadioButton source, JRadioButton alpha, ButtonGroup group, String tip) {
        group.add(source);
        group.add(alpha);
        source.setFont(UiFonts.ui(11f));
        alpha.setFont(UiFonts.ui(11f));
        source.setMargin(new Insets(0, 2, 0, 2));
        alpha.setMargin(new Insets(0, 2, 0, 2));
        source.setToolTipText(tip);
        alpha.setToolTipText(tip);
        source.addActionListener(e -> showMembers(classes.selected()));
        alpha.addActionListener(e -> showMembers(classes.selected()));
    }

    private static JPanel sortRadios(JRadioButton source, JRadioButton alpha) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        panel.setOpaque(false);
        panel.add(source);
        panel.add(alpha);
        return panel;
    }

    private static SortMode selected(JRadioButton alpha) {
        return alpha.isSelected() ? SortMode.ALPHA : SortMode.SOURCE;
    }

    private static List<Symbol> filterTypes(List<Symbol> types, String query) {
        if (query.isEmpty()) {
            return types;
        }
        List<Symbol> shown = new ArrayList<>();
        for (Symbol type : types) {
            if (contains(type.label(), query) || hasMatchingMember(type, query)) {
                shown.add(type);
            }
        }
        return shown;
    }

    private static boolean hasMatchingMember(Symbol type, String query) {
        for (Symbol child : type.children()) {
            if (child.kind() != Kind.TYPE && contains(child.label(), query)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String label, String query) {
        return label != null && label.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static List<Symbol> sort(List<Symbol> rows, SortMode mode) {
        List<Symbol> copy = new ArrayList<>(rows);
        if (mode == SortMode.ALPHA) {
            copy.sort(Comparator.comparing(Symbol::label, String.CASE_INSENSITIVE_ORDER));
        } else {
            copy.sort(Comparator.comparingInt(Symbol::line));
        }
        return copy;
    }

    private static List<Symbol> flattenTypes(List<Symbol> symbols) {
        List<Symbol> types = new ArrayList<>();
        collectTypes(symbols, types);
        return types;
    }

    private static void collectTypes(List<Symbol> symbols, List<Symbol> out) {
        for (Symbol symbol : symbols) {
            if (symbol.kind() != Kind.TYPE) {
                continue;
            }
            out.add(symbol);
            collectTypes(symbol.children(), out);
        }
    }

    private static int indexOf(List<Symbol> types, Symbol previous) {
        if (previous == null) {
            return -1;
        }
        for (int i = 0; i < types.size(); i++) {
            Symbol type = types.get(i);
            if (type.line() == previous.line() && type.label().equals(previous.label())) {
                return i;
            }
        }
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).label().equals(previous.label())) {
                return i;
            }
        }
        return -1;
    }

    private static JPanel section(String title, JComponent sort, SymbolTable table) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        JLabel label = new JLabel(title);
        label.setFont(UiFonts.ui(12f));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        header.add(label, BorderLayout.WEST);
        if (sort != null) {
            header.add(sort, BorderLayout.EAST);
        }
        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(table.table), BorderLayout.CENTER);
        return panel;
    }

    private static final class SymbolTable {
        private final Model model = new Model();
        private final JTable table = new JTable(model);
        private java.util.function.Consumer<Symbol> onSelect = s -> {
        };

        SymbolTable(String lineHeader, String nameHeader) {
            model.lineHeader = lineHeader;
            model.nameHeader = nameHeader;
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            RowSelection.install(table);
            table.setRowHeight(20);
            table.setFillsViewportHeight(true);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
            table.getColumnModel().getColumn(0).setPreferredWidth(48);
            table.getColumnModel().getColumn(0).setMaxWidth(64);
            DefaultTableCellRenderer center = new DefaultTableCellRenderer();
            center.setHorizontalAlignment(SwingConstants.RIGHT);
            table.getColumnModel().getColumn(0).setCellRenderer(center);
            table.getSelectionModel().addListSelectionListener(e -> {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                onSelect.accept(selected());
            });
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger()) {
                        return;
                    }
                    int viewRow = table.rowAtPoint(e.getPoint());
                    // A row that is already selected does not fire a selection event.
                    if (viewRow >= 0 && viewRow == table.getSelectedRow()) {
                        onSelect.accept(symbolAt(viewRow));
                    }
                }
            });
        }

        private Symbol symbolAt(int viewRow) {
            if (viewRow < 0) {
                return null;
            }
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow < 0 || modelRow >= model.rows.size()) {
                return null;
            }
            return model.rows.get(modelRow);
        }

        void onSelect(java.util.function.Consumer<Symbol> onSelect) {
            this.onSelect = onSelect != null ? onSelect : s -> {
            };
        }

        void setRows(List<Symbol> rows) {
            model.rows = rows == null ? List.of() : List.copyOf(rows);
            model.fireTableDataChanged();
            table.clearSelection();
        }

        void select(int index) {
            if (index < 0 || index >= model.rows.size()) {
                table.clearSelection();
                return;
            }
            table.setRowSelectionInterval(index, index);
            table.scrollRectToVisible(table.getCellRect(index, 0, true));
        }

        Symbol selected() {
            int row = table.getSelectedRow();
            if (row < 0) {
                return null;
            }
            int modelRow = table.convertRowIndexToModel(row);
            if (modelRow < 0 || modelRow >= model.rows.size()) {
                return null;
            }
            return model.rows.get(modelRow);
        }
    }

    private static final class Model extends AbstractTableModel {
        private String lineHeader = "行数";
        private String nameHeader = "名前";
        private List<Symbol> rows = List.of();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? lineHeader : nameHeader;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Symbol symbol = rows.get(rowIndex);
            return columnIndex == 0 ? symbol.line() : symbol.label();
        }
    }
}
