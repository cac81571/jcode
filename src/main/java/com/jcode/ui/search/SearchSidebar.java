package com.jcode.ui.search;

import com.jcode.model.ReplaceResult;
import com.jcode.model.SearchFileGroup;
import com.jcode.model.SearchMatch;
import com.jcode.service.FilePathFilter;
import com.jcode.service.RipgrepLocator;
import com.jcode.service.SearchHistory;
import com.jcode.service.SearchReportBuilder;
import com.jcode.service.SearchService;
import com.jcode.service.WorkspaceSearch;
import com.jcode.ui.ClipboardPaths;
import com.jcode.ui.PathEllipsis;
import com.jcode.ui.ThemeManager;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Workspace-wide search sidebar (VSCode Search view).
 * Results can be shown as a text tree or a flat table.
 */
public final class SearchSidebar extends JPanel {

    private static final int PATH_MAX = 36;

    private final DefaultComboBoxModel<String> historyModel = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<String> includeFileHistoryModel = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<String> includeDirHistoryModel = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<String> excludeFileHistoryModel = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<String> excludeDirHistoryModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> queryField = new JComboBox<>(historyModel);
    private final JTextField replaceField = new JTextField();
    private final JComboBox<String> includeFileField = new JComboBox<>(includeFileHistoryModel);
    private final JComboBox<String> includeDirField = new JComboBox<>(includeDirHistoryModel);
    private final JComboBox<String> excludeFileField = new JComboBox<>(excludeFileHistoryModel);
    private final JComboBox<String> excludeDirField = new JComboBox<>(excludeDirHistoryModel);
    private final JCheckBox matchCase = new JCheckBox("大文字小文字を区別");
    private final JCheckBox wholeWord = new JCheckBox("単語単位");
    private final JCheckBox regex = new JCheckBox("正規表現");
    private final JCheckBox openFileOnly = new JCheckBox("開いているファイルのみ");
    private final JSpinner contextSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 20, 1));
    private final JLabel summary = new JLabel(" ");
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("results");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree resultsTree = new JTree(treeModel);
    private final ResultTableModel resultRows = new ResultTableModel();
    private final JTable resultsTable = new JTable(resultRows);
    private final CardLayout resultCards = new CardLayout();
    private final JPanel resultBody = new JPanel(resultCards);
    private final JToggleButton treeModeBtn = new JToggleButton("ツリー", true);
    private final JToggleButton tableModeBtn = new JToggleButton("テーブル", false);
    private final JButton copyTsvBtn = new JButton("TSVコピー");
    private final JButton searchBtn = new JButton("検索");
    private final JButton cancelBtn = new JButton("キャンセル");
    private final JButton collapseAllBtn = new JButton("全て折りたたむ");
    private final JButton expandAllBtn = new JButton("全て展開");
    private final JPanel foldBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    private final JButton tableViewBtn = new JButton("テーブル");
    private final JButton editorViewBtn = new JButton("エディタ");
    private final JButton replaceAllBtn = new JButton("すべて置換");
    private final Supplier<Path> workspaceRoot;
    private Supplier<ActiveFile> activeFile = () -> null;
    private BiConsumer<Path, SearchMatch> onOpenMatch = (p, m) -> {
    };
    private Consumer<SearchEditorRequest> onOpenInEditor = r -> {
    };
    private Consumer<SearchTableRequest> onOpenTable = r -> {
    };
    private Consumer<ReplaceResult> onReplaced = r -> {
    };

    /** Currently focused editor file (disk path + buffer text, including unsaved edits). */
    public record ActiveFile(Path path, String text) {
    }

    private List<SearchFileGroup> lastGroups = List.of();
    private List<SearchMatch> flatMatches = List.of();
    private String lastQuery = "";
    /** Stable id so this search's editor/table tabs are not shared with other searches. */
    private String sessionKey = "1";
    private Consumer<String> onSearched = query -> {
    };
    /** Incremented on each search; stale SwingWorkers ignore their done(). */
    private int searchGeneration;
    private boolean searching;
    private WorkspaceSearch.Handle activeSearch;
    private final Map<Path, DefaultMutableTreeNode> fileNodes = new LinkedHashMap<>();
    /** Shown while a workspace search is running, e.g. {@code ripgrep}. */
    private String runningNote = "";

    public SearchSidebar(Supplier<Path> workspaceRoot) {
        super(new BorderLayout(0, 8));
        this.workspaceRoot = workspaceRoot;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        matchCase.setToolTipText("大文字と小文字を区別して検索");
        wholeWord.setToolTipText("単語単位で一致");
        regex.setToolTipText("正規表現で検索");
        openFileOnly.setToolTipText("エディタで選択中のファイルだけを検索（未保存の内容も対象）。Include / Exclude も適用");
        contextSpinner.setToolTipText("右側に開くときの前後行数 (±N)。テキストとテーブルの両方に適用");
        ((JSpinner.DefaultEditor) contextSpinner.getEditor()).getTextField().setColumns(2);

        queryField.setEditable(true);
        queryField.setToolTipText("検索キーワード（履歴から選択可 / 右クリックで履歴クリア）");
        configureFilter(
                includeFileField,
                "対象ファイル（カンマ区切りの glob。空ならすべて）",
                "例: *.java, **/Foo.java",
                SearchHistory.Kind.INCLUDE_FILE,
                "Include(file) 履歴をクリア");
        configureFilter(
                includeDirField,
                "対象フォルダ（カンマ区切り。配下のファイルを検索。空ならすべて）",
                "例: src, src/main",
                SearchHistory.Kind.INCLUDE_DIR,
                "Include(dir) 履歴をクリア");
        configureFilter(
                excludeFileField,
                "除外ファイル（カンマ区切りの glob）",
                "例: *.min.js",
                SearchHistory.Kind.EXCLUDE_FILE,
                "Exclude(file) 履歴をクリア");
        configureFilter(
                excludeDirField,
                "除外フォルダ（カンマ区切り。その配下は検索しない）",
                "例: target, test",
                SearchHistory.Kind.EXCLUDE_DIR,
                "Exclude(dir) 履歴をクリア");
        reloadHistoryModel();
        reloadFilterHistoryModels();
        installHistoryPopup(queryField, SearchHistory.Kind.QUERY, "検索履歴をクリア");

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        opts.add(matchCase);
        opts.add(wholeWord);
        opts.add(regex);

        JButton extensionsBtn = new JButton("対象拡張子…");
        extensionsBtn.setToolTipText("Include(file) が空のときに検索する拡張子");
        extensionsBtn.addActionListener(e -> {
            if (SearchExtensionsDialog.show(this) && !searching) {
                summary.setText("検索の対象拡張子を保存しました");
            }
        });

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.add(extensionsBtn, BorderLayout.WEST);
        titleRow.add(opts, BorderLayout.EAST);

        replaceField.putClientProperty("JTextField.placeholderText", "置換文字列");
        replaceField.setToolTipText("置換後の文字列");

        searchBtn.setToolTipText("検索 (Enter)");
        searchBtn.addActionListener(e -> runSearch());
        searchOnEnter(queryField);
        searchOnEnter(replaceField);
        searchOnEnter(includeFileField);
        searchOnEnter(includeDirField);
        searchOnEnter(excludeFileField);
        searchOnEnter(excludeDirField);
        searchOnEnter(((JSpinner.DefaultEditor) contextSpinner.getEditor()).getTextField());
        cancelBtn.setVisible(false);
        cancelBtn.setToolTipText("検索を中止 (Esc)");
        cancelBtn.addActionListener(e -> cancelSearch());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelSearch");
        getActionMap().put("cancelSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelSearch();
            }
        });

        replaceAllBtn.setEnabled(false);
        replaceAllBtn.setToolTipText("検索結果のファイルすべてで置換（要確認）");
        replaceAllBtn.addActionListener(e -> runReplaceAll());
        equalizeSearchButtonWidth();

        collapseAllBtn.setToolTipText("検索結果をすべて折りたたむ");
        expandAllBtn.setToolTipText("検索結果をすべて展開する");
        collapseAllBtn.addActionListener(e -> collapseAll());
        expandAllBtn.addActionListener(e -> expandAll());

        JPanel queryRow = new JPanel(new BorderLayout(4, 0));
        queryRow.add(queryField, BorderLayout.CENTER);
        queryRow.add(searchBtn, BorderLayout.EAST);

        JPanel replaceRow = new JPanel(new BorderLayout(4, 0));
        replaceRow.add(replaceField, BorderLayout.CENTER);
        replaceRow.add(replaceAllBtn, BorderLayout.EAST);

        JLabel includeFileLabel = new JLabel("Include(file)");
        JLabel includeDirLabel = new JLabel("Include(dir)");
        JLabel excludeFileLabel = new JLabel("Exclude(file)");
        JLabel excludeDirLabel = new JLabel("Exclude(dir)");
        equalizeLabelWidth(includeFileLabel, includeDirLabel, excludeFileLabel, excludeDirLabel);

        JPanel includeFileRow = labeledRow(includeFileLabel, includeFileField);
        JPanel includeDirRow = labeledRow(includeDirLabel, includeDirField);
        JPanel excludeFileRow = labeledRow(excludeFileLabel, excludeFileField);
        JPanel excludeDirRow = labeledRow(excludeDirLabel, excludeDirField);

        tableViewBtn.setEnabled(false);
        editorViewBtn.setEnabled(false);
        tableViewBtn.setToolTipText("検索結果をテーブルとして右側に開く（前後 N 行。● がヒット行）");
        editorViewBtn.setToolTipText("検索結果をテキストとして右側に開く（前後 N 行）");
        tableViewBtn.addActionListener(e -> openResults(true));
        editorViewBtn.addActionListener(e -> openResults(false));

        ButtonGroup resultModes = new ButtonGroup();
        resultModes.add(treeModeBtn);
        resultModes.add(tableModeBtn);
        treeModeBtn.setToolTipText("検索結果をツリーで表示");
        tableModeBtn.setToolTipText("検索結果をテーブルで表示");
        treeModeBtn.addActionListener(e -> showResultMode(false));
        tableModeBtn.addActionListener(e -> showResultMode(true));

        JPanel editorActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        editorActions.add(treeModeBtn);
        editorActions.add(tableModeBtn);
        editorActions.add(new JLabel("前後"));
        editorActions.add(contextSpinner);
        editorActions.add(new JLabel("行"));
        editorActions.add(tableViewBtn);
        editorActions.add(editorViewBtn);

        foldBtns.add(expandAllBtn);
        foldBtns.add(collapseAllBtn);
        copyTsvBtn.setToolTipText("テーブルをタブ区切りでクリップボードにコピー");
        copyTsvBtn.setVisible(false);
        copyTsvBtn.addActionListener(e -> copyResultsTsv(null));

        JButton useHitFiles = new JButton("ヒットしたファイルで再検索");
        useHitFiles.setToolTipText("ヒットしたファイルのパスを Include(file) にセットします");
        useHitFiles.addActionListener(e -> applyHitFilesAsInclude());
        JPanel hitFileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        hitFileRow.add(useHitFiles);
        hitFileRow.add(openFileOnly);

        JPanel filterBlock = new JPanel();
        filterBlock.setLayout(new BoxLayout(filterBlock, BoxLayout.Y_AXIS));
        includeFileRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        includeDirRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        excludeFileRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        excludeDirRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        hitFileRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        editorActions.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterBlock.add(includeFileRow);
        filterBlock.add(Box.createVerticalStrut(4));
        filterBlock.add(includeDirRow);
        filterBlock.add(Box.createVerticalStrut(4));
        filterBlock.add(excludeFileRow);
        filterBlock.add(Box.createVerticalStrut(4));
        filterBlock.add(excludeDirRow);
        filterBlock.add(Box.createVerticalStrut(4));
        filterBlock.add(hitFileRow);
        filterBlock.add(Box.createVerticalStrut(4));
        filterBlock.add(editorActions);

        JPanel mid = new JPanel(new BorderLayout(4, 4));
        mid.add(replaceRow, BorderLayout.NORTH);
        mid.add(filterBlock, BorderLayout.CENTER);

        JPanel fields = new JPanel(new BorderLayout(4, 4));
        fields.add(queryRow, BorderLayout.NORTH);
        fields.add(mid, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout(4, 4));
        north.add(titleRow, BorderLayout.NORTH);
        north.add(fields, BorderLayout.CENTER);

        setupResultsTree();
        setupResultsTable();

        JPanel summaryStatus = new JPanel(new BorderLayout(8, 0));
        summaryStatus.add(summary, BorderLayout.CENTER);
        summaryStatus.add(cancelBtn, BorderLayout.EAST);

        JPanel resultTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        resultTools.add(copyTsvBtn);
        resultTools.add(foldBtns);

        JPanel summaryRow = new JPanel(new BorderLayout(4, 0));
        summaryRow.add(summaryStatus, BorderLayout.CENTER);
        summaryRow.add(resultTools, BorderLayout.EAST);

        resultBody.add(new JScrollPane(resultsTree), "tree");
        resultBody.add(new JScrollPane(resultsTable), "table");

        JPanel resultsArea = new JPanel(new BorderLayout(0, 4));
        resultsArea.add(summaryRow, BorderLayout.NORTH);
        resultsArea.add(resultBody, BorderLayout.CENTER);

        add(north, BorderLayout.NORTH);
        add(resultsArea, BorderLayout.CENTER);
    }

    private void setupResultsTree() {
        resultsTree.setRootVisible(false);
        resultsTree.setShowsRootHandles(true);
        resultsTree.setRowHeight(0);
        resultsTree.setCellRenderer(new ResultTreeRenderer());
        resultsTree.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                resultsTree.repaint();
            }
        });
        resultsTree.addTreeSelectionListener(e -> {
            Object last = resultsTree.getLastSelectedPathComponent();
            if (!(last instanceof DefaultMutableTreeNode node)) {
                return;
            }
            Object user = node.getUserObject();
            if (user instanceof SearchMatch match) {
                onOpenMatch.accept(match.file(), match);
            } else if (user instanceof SearchFileGroup group && !group.matches().isEmpty()) {
                SearchMatch first = group.matches().getFirst();
                onOpenMatch.accept(first.file(), first);
            }
        });
        ClipboardPaths.install(resultsTree, this::selectedResultPath);
    }

    private void setupResultsTable() {
        resultsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        resultsTable.setRowSelectionAllowed(true);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        resultsTable.setFillsViewportHeight(true);
        resultsTable.setShowGrid(false);
        resultsTable.setRowHeight(22);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(48);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(64);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(56);
        resultsTable.getColumnModel().getColumn(2).setMaxWidth(72);
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(140);
        resultsTable.setToolTipText("クリックでファイルを開く。Ctrl+C で選択行を TSV コピー");
        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int row = resultsTable.getSelectedRow();
            if (row < 0) {
                return;
            }
            SearchMatch match = resultRows.matchAt(resultsTable.convertRowIndexToModel(row));
            if (match != null) {
                onOpenMatch.accept(match.file(), match);
            }
        });
        resultsTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAll");
        resultsTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyResultsTsv");
        resultsTable.getActionMap().put("copyResultsTsv", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int[] selected = resultsTable.getSelectedRows();
                copyResultsTsv(selected.length == 0 ? null : selected);
            }
        });
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("TSV形式でコピー");
        copy.addActionListener(e -> copyResultsTsv(null));
        menu.add(copy);
        resultsTable.setComponentPopupMenu(menu);
    }

    private void showResultMode(boolean table) {
        resultCards.show(resultBody, table ? "table" : "tree");
        copyTsvBtn.setVisible(table);
        foldBtns.setVisible(!table);
        resultBody.revalidate();
    }

    /** {@code viewRows} null copies every row. Rows are view indexes. */
    private void copyResultsTsv(int[] viewRows) {
        StringBuilder out = new StringBuilder();
        int cols = resultRows.getColumnCount();
        for (int col = 0; col < cols; col++) {
            if (col > 0) {
                out.append('\t');
            }
            out.append(tsvCell(resultRows.getColumnName(col)));
        }
        int count = viewRows == null ? resultRows.getRowCount() : viewRows.length;
        for (int i = 0; i < count; i++) {
            int row = viewRows == null ? i : resultsTable.convertRowIndexToModel(viewRows[i]);
            out.append("\r\n");
            for (int col = 0; col < cols; col++) {
                if (col > 0) {
                    out.append('\t');
                }
                out.append(tsvCell(resultRows.getValueAt(row, col)));
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(out.toString()), null);
        summary.setText("TSVをコピーしました（" + count + " 行）");
    }

    private static String tsvCell(Object value) {
        String text = value == null ? "" : value.toString();
        text = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return text;
    }

    public void setOnOpenMatch(BiConsumer<Path, SearchMatch> onOpenMatch) {
        this.onOpenMatch = onOpenMatch;
    }

    public void setActiveFileSupplier(Supplier<ActiveFile> activeFile) {
        this.activeFile = activeFile != null ? activeFile : () -> null;
    }

    public String lastQuery() {
        return lastQuery;
    }

    public boolean isMatchCase() {
        return matchCase.isSelected();
    }

    public boolean isWholeWord() {
        return wholeWord.isSelected();
    }

    public boolean isRegex() {
        return regex.isSelected();
    }

    public void setOnOpenInEditor(Consumer<SearchEditorRequest> onOpenInEditor) {
        this.onOpenInEditor = onOpenInEditor;
    }

    public void setOnOpenTable(Consumer<SearchTableRequest> onOpenTable) {
        this.onOpenTable = onOpenTable != null ? onOpenTable : r -> {
        };
    }

    public void setOnReplaced(Consumer<ReplaceResult> onReplaced) {
        this.onReplaced = onReplaced;
    }

    public void setSessionKey(String sessionKey) {
        if (sessionKey != null && !sessionKey.isBlank()) {
            this.sessionKey = sessionKey;
        }
    }

    public String sessionKey() {
        return sessionKey;
    }

    /** Fired with the query when a search starts, or with {@code ""} when the query is cleared. */
    public void setOnSearched(Consumer<String> onSearched) {
        this.onSearched = onSearched != null ? onSearched : query -> {
        };
    }

    /** Sets Include(dir) without starting a search. */
    public void searchInDirectory(String relativeDir) {
        String value = relativeDir == null ? "" : relativeDir.replace('\\', '/');
        includeDirField.getEditor().setItem(value);
        focusSearch();
    }

    public void focusSearch() {
        queryField.requestFocusInWindow();
        Component editor = queryField.getEditor().getEditorComponent();
        if (editor instanceof JTextComponent text) {
            text.selectAll();
        }
    }

    private String currentQuery() {
        return comboText(queryField);
    }

    private static String comboText(JComboBox<String> combo) {
        Object item = combo.getEditor().getItem();
        if (item == null) {
            item = combo.getSelectedItem();
        }
        return item == null ? "" : item.toString();
    }

    private void reloadHistoryModel() {
        reloadComboHistory(queryField, historyModel, SearchHistory.Kind.QUERY);
    }

    private void configureFilter(
            JComboBox<String> field,
            String tip,
            String placeholder,
            SearchHistory.Kind kind,
            String clearLabel
    ) {
        field.setEditable(true);
        field.setToolTipText(tip);
        setComboPlaceholder(field, placeholder);
        installHistoryPopup(field, kind, clearLabel);
    }

    private void reloadFilterHistoryModels() {
        reloadComboHistory(includeFileField, includeFileHistoryModel, SearchHistory.Kind.INCLUDE_FILE);
        reloadComboHistory(includeDirField, includeDirHistoryModel, SearchHistory.Kind.INCLUDE_DIR);
        reloadComboHistory(excludeFileField, excludeFileHistoryModel, SearchHistory.Kind.EXCLUDE_FILE);
        reloadComboHistory(excludeDirField, excludeDirHistoryModel, SearchHistory.Kind.EXCLUDE_DIR);
    }

    private static void reloadComboHistory(
            JComboBox<String> combo,
            DefaultComboBoxModel<String> model,
            SearchHistory.Kind kind
    ) {
        String current = comboText(combo);
        model.removeAllElements();
        for (String item : SearchHistory.all(kind)) {
            model.addElement(item);
        }
        combo.getEditor().setItem(current);
    }

    private void installHistoryPopup(JComboBox<String> combo, SearchHistory.Kind kind, String clearLabel) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem(clearLabel);
        clear.addActionListener(e -> {
            SearchHistory.clear(kind);
            if (kind == SearchHistory.Kind.QUERY) {
                reloadHistoryModel();
            } else {
                reloadFilterHistoryModels();
            }
            summary.setText(clearLabel.replace("をクリア", "をクリアしました"));
        });
        menu.add(clear);
        combo.setComponentPopupMenu(menu);
        Component editor = combo.getEditor().getEditorComponent();
        if (editor instanceof JTextComponent text) {
            text.setComponentPopupMenu(menu);
        }
    }

    private void searchOnEnter(JComboBox<String> combo) {
        searchOnEnter(combo.getEditor().getEditorComponent());
    }

    /** Enter in the search form runs the search. Combo popups still use Enter to pick a history item. */
    private void searchOnEnter(Component component) {
        component.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_ESCAPE || e.getModifiersEx() != 0 || !searching) {
                    return;
                }
                if (component.getParent() instanceof JComboBox<?> combo && combo.isPopupVisible()) {
                    return;
                }
                e.consume();
                cancelSearch();
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_ENTER || e.getModifiersEx() != 0) {
                    return;
                }
                if (component.getParent() instanceof JComboBox<?> combo && combo.isPopupVisible()) {
                    return;
                }
                runSearch();
            }
        });
    }

    private void runSearch() {
        String query = currentQuery().trim();
        if (query.isEmpty()) {
            stopActiveSearch();
            clearResults();
            summary.setText(" ");
            onSearched.accept("");
            return;
        }

        boolean onlyOpen = openFileOnly.isSelected();
        ActiveFile open = onlyOpen ? activeFile.get() : null;
        Path root = workspaceRoot.get();
        if (onlyOpen) {
            if (open == null || open.path() == null) {
                stopActiveSearch();
                clearResults();
                summary.setText("開いているファイルがありません");
                setResultActionsEnabled(false);
                replaceAllBtn.setEnabled(false);
                return;
            }
        } else if (root == null) {
            stopActiveSearch();
            summary.setText("フォルダを開いてください");
            return;
        }

        String includeFiles = comboText(includeFileField).trim();
        String includeDirs = comboText(includeDirField).trim();
        String excludeFiles = comboText(excludeFileField).trim();
        String excludeDirs = comboText(excludeDirField).trim();

        SearchHistory.remember(query);
        SearchHistory.remember(SearchHistory.Kind.INCLUDE_FILE, includeFiles);
        SearchHistory.remember(SearchHistory.Kind.INCLUDE_DIR, includeDirs);
        SearchHistory.remember(SearchHistory.Kind.EXCLUDE_FILE, excludeFiles);
        SearchHistory.remember(SearchHistory.Kind.EXCLUDE_DIR, excludeDirs);
        reloadHistoryModel();
        reloadFilterHistoryModels();
        queryField.getEditor().setItem(query);
        includeFileField.getEditor().setItem(includeFiles);
        includeDirField.getEditor().setItem(includeDirs);
        excludeFileField.getEditor().setItem(excludeFiles);
        excludeDirField.getEditor().setItem(excludeDirs);

        if (activeSearch != null) {
            activeSearch.cancel();
        }
        final int generation = ++searchGeneration;
        final WorkspaceSearch.Handle handle = new WorkspaceSearch.Handle();
        activeSearch = handle;
        setSearching(true);
        final Path rg = onlyOpen ? null : RipgrepLocator.resolve();
        runningNote = onlyOpen ? "" : (rg == null ? "Java・ripgrep なし" : "ripgrep");
        summary.setText(runningNote.isEmpty() ? "検索中…" : "検索中…（" + runningNote + "）");
        onSearched.accept(query);
        clearResultsKeepingHistory();
        lastQuery = query;
        boolean mc = matchCase.isSelected();
        boolean ww = wholeWord.isSelected();
        boolean rx = regex.isSelected();
        final ActiveFile target = open;
        final Path searchRoot = root;
        final WorkspaceSearch.Request request = onlyOpen ? null : new WorkspaceSearch.Request(
                searchRoot, query, mc, ww, rx, includeFiles, includeDirs, excludeFiles, excludeDirs);

        new SwingWorker<WorkspaceSearch.Outcome, List<SearchMatch>>() {
            @Override
            protected WorkspaceSearch.Outcome doInBackground() {
                if (handle.isCancelled()) {
                    return WorkspaceSearch.Outcome.cancelled(
                            rg == null ? WorkspaceSearch.Engine.JAVA : WorkspaceSearch.Engine.RIPGREP);
                }
                if (onlyOpen) {
                    Path filterRoot = searchRoot != null
                            ? searchRoot
                            : (target.path().getParent() != null ? target.path().getParent() : target.path());
                    FilePathFilter filter = FilePathFilter.parse(
                            includeFiles, includeDirs, excludeFiles, excludeDirs);
                    if (filter.accept(filterRoot, target.path())) {
                        List<SearchMatch> found = SearchService.findInText(
                                target.text(), target.path(), query, mc, ww, rx);
                        if (!found.isEmpty() && !handle.isCancelled()) {
                            publish(found);
                        }
                    }
                    if (handle.isCancelled()) {
                        return WorkspaceSearch.Outcome.cancelled(WorkspaceSearch.Engine.JAVA);
                    }
                    return new WorkspaceSearch.Outcome(WorkspaceSearch.Engine.JAVA, false, null, null);
                }
                return WorkspaceSearch.run(rg, request, handle, this::publish);
            }

            @Override
            protected void process(List<List<SearchMatch>> chunks) {
                if (generation != searchGeneration) {
                    return;
                }
                for (List<SearchMatch> chunk : chunks) {
                    ingest(chunk);
                }
                updateRunningSummary();
            }

            @Override
            protected void done() {
                if (generation != searchGeneration) {
                    return;
                }
                setSearching(false);
                try {
                    WorkspaceSearch.Outcome outcome = get();
                    finishSearch(query, outcome);
                } catch (Exception ex) {
                    if (generation != searchGeneration) {
                        return;
                    }
                    snapshotResults();
                    summary.setText("検索失敗: " + ex.getMessage());
                    setResultActionsEnabled(!lastGroups.isEmpty());
                    replaceAllBtn.setEnabled(!lastGroups.isEmpty());
                }
            }
        }.execute();
    }

    private void finishSearch(String query, WorkspaceSearch.Outcome outcome) {
        snapshotResults();
        lastQuery = query;
        int hits = lastGroups.stream().mapToInt(SearchFileGroup::hitCount).sum();
        if (outcome != null && outcome.cancelled()) {
            summary.setText(hits == 0
                    ? "キャンセルしました"
                    : "キャンセルしました · " + lastGroups.size() + " ファイル · " + hits + " 件");
        } else if (outcome != null && outcome.error() != null) {
            summary.setText("検索失敗: " + outcome.error());
        } else {
            String suffix = outcome == null ? null : outcome.summarySuffix();
            summary.setText(resultSummary(lastGroups.size(), hits, suffix));
        }
        setResultActionsEnabled(!lastGroups.isEmpty());
        replaceAllBtn.setEnabled(!lastGroups.isEmpty());
        if (!lastGroups.isEmpty()) {
            expandAll();
        }
    }

    private void updateRunningSummary() {
        int hits = 0;
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            Object user = ((DefaultMutableTreeNode) rootNode.getChildAt(i)).getUserObject();
            if (user instanceof SearchFileGroup group) {
                hits += group.hitCount();
            }
        }
        String text = "検索中… " + rootNode.getChildCount() + " ファイル · " + hits + " 件";
        if (runningNote != null && !runningNote.isEmpty()) {
            text += "（" + runningNote + "）";
        }
        summary.setText(text);
    }

    private static String resultSummary(int files, int hits, String suffix) {
        String text = files + " ファイル · " + hits + " 件";
        if (suffix != null && !suffix.isBlank()) {
            text += "（" + suffix + "）";
        }
        return text;
    }

    private void ingest(List<SearchMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        int tableFrom = resultRows.getRowCount();
        for (SearchMatch match : matches) {
            if (match == null || match.file() == null) {
                continue;
            }
            DefaultMutableTreeNode fileNode = fileNodes.get(match.file());
            if (fileNode == null) {
                SearchFileGroup group = new SearchFileGroup(match.file(), new ArrayList<>());
                fileNode = new DefaultMutableTreeNode(group);
                int index = fileInsertIndex(match.file());
                rootNode.insert(fileNode, index);
                treeModel.nodesWereInserted(rootNode, new int[]{index});
                fileNodes.put(match.file(), fileNode);
                // The new file node is still a leaf. expandPath is a no-op for leaves and
                // does not expand the hidden root, so reveal the row via its parent instead.
                resultsTree.makeVisible(new TreePath(fileNode.getPath()));
            }
            SearchFileGroup group = (SearchFileGroup) fileNode.getUserObject();
            List<SearchMatch> list = group.matches();
            int at = matchInsertIndex(list, match);
            if (at < list.size()) {
                SearchMatch existing = list.get(at);
                if (existing.lineNumber() == match.lineNumber()
                        && existing.startColumn() == match.startColumn()
                        && existing.endColumn() == match.endColumn()) {
                    continue;
                }
            }
            list.add(at, match);
            resultRows.add(match);
            fileNode.insert(new DefaultMutableTreeNode(match), at);
            treeModel.nodesWereInserted(fileNode, new int[]{at});
            treeModel.nodeChanged(fileNode);
            // Now that the file node has a child it is no longer a leaf, so this also
            // expands the hidden root if clear/reload left it collapsed.
            resultsTree.expandPath(new TreePath(fileNode.getPath()));
        }
        resultRows.fireAdded(tableFrom);
    }

    private int fileInsertIndex(Path file) {
        String key = file.toString();
        int count = rootNode.getChildCount();
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(mid);
            SearchFileGroup group = (SearchFileGroup) child.getUserObject();
            if (group.file().toString().compareTo(key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int matchInsertIndex(List<SearchMatch> list, SearchMatch match) {
        int lo = 0;
        int hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            SearchMatch existing = list.get(mid);
            int cmp = Integer.compare(existing.lineNumber(), match.lineNumber());
            if (cmp == 0) {
                cmp = Integer.compare(existing.startColumn(), match.startColumn());
            }
            if (cmp == 0) {
                cmp = Integer.compare(existing.endColumn(), match.endColumn());
            }
            if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void snapshotResults() {
        List<SearchFileGroup> groups = new ArrayList<>();
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (node.getUserObject() instanceof SearchFileGroup group) {
                groups.add(new SearchFileGroup(group.file(), List.copyOf(group.matches())));
            }
        }
        lastGroups = List.copyOf(groups);
        flatMatches = flattenMatches(lastGroups);
    }

    private void stopActiveSearch() {
        if (activeSearch != null) {
            activeSearch.cancel();
        }
        if (searching) {
            searchGeneration++;
            setSearching(false);
        }
    }

    private void cancelSearch() {
        if (!searching) {
            return;
        }
        if (activeSearch != null) {
            activeSearch.cancel();
        }
        searchGeneration++;
        setSearching(false);
        snapshotResults();
        int hits = lastGroups.stream().mapToInt(SearchFileGroup::hitCount).sum();
        summary.setText(hits == 0
                ? "キャンセルしました"
                : "キャンセルしました · " + lastGroups.size() + " ファイル · " + hits + " 件");
        setResultActionsEnabled(!lastGroups.isEmpty());
        replaceAllBtn.setEnabled(!lastGroups.isEmpty());
    }

    private void setSearching(boolean searching) {
        this.searching = searching;
        searchBtn.setEnabled(!searching);
        cancelBtn.setVisible(searching);
        cancelBtn.getParent().revalidate();
    }

    private void equalizeSearchButtonWidth() {
        int width = Math.max(searchBtn.getPreferredSize().width, replaceAllBtn.getPreferredSize().width);
        for (JButton button : List.of(searchBtn, replaceAllBtn)) {
            Dimension size = button.getPreferredSize();
            Dimension equal = new Dimension(width, size.height);
            button.setPreferredSize(equal);
            button.setMinimumSize(equal);
        }
    }

    private void applyHitFilesAsInclude() {
        if (lastGroups.isEmpty()) {
            summary.setText("ヒットしたファイルがありません");
            return;
        }
        Path root = workspaceRoot.get();
        StringBuilder value = new StringBuilder();
        int count = 0;
        for (SearchFileGroup group : lastGroups) {
            if (group.file() == null) {
                continue;
            }
            if (count > 0) {
                value.append(", ");
            }
            value.append(includePattern(root, group.file()));
            count++;
        }
        includeFileField.getEditor().setItem(value.toString());
        summary.setText(count + " ファイルを Include(file) に設定しました");
    }

    private static String includePattern(Path root, Path file) {
        try {
            if (root != null) {
                return root.toAbsolutePath().normalize()
                        .relativize(file.toAbsolutePath().normalize())
                        .toString()
                        .replace('\\', '/');
            }
        } catch (IllegalArgumentException ignored) {
            // file is outside the workspace
        }
        return file.getFileName() == null ? file.toString() : file.getFileName().toString();
    }

    private void runReplaceAll() {
        if (lastGroups.isEmpty() || lastQuery.isEmpty()) {
            summary.setText("先に検索してください");
            return;
        }
        Path root = workspaceRoot.get();
        if (root == null) {
            summary.setText("フォルダを開いてください");
            return;
        }

        int hits = lastGroups.stream().mapToInt(SearchFileGroup::hitCount).sum();
        String replacement = replaceField.getText();
        StringBuilder confirm = new StringBuilder();
        confirm.append(lastGroups.size()).append(" ファイル / ").append(hits).append(" 件を\n")
                .append("「").append(truncateLine(lastQuery, 40)).append("」→「")
                .append(truncateLine(replacement, 40)).append("」\n")
                .append("に置換しますか？");
        // Git 管理下なら Discard 等で戻せるので警告文は出さない
        if (!isUnderGit(root)) {
            confirm.append("\n（元に戻す操作はありません）");
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                confirm.toString(),
                "すべて置換",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        List<Path> files = lastGroups.stream().map(SearchFileGroup::file).toList();
        boolean mc = matchCase.isSelected();
        boolean ww = wholeWord.isSelected();
        boolean rx = regex.isSelected();
        String query = lastQuery;

        replaceAllBtn.setEnabled(false);
        summary.setText("置換中…");

        new SwingWorker<ReplaceResult, Void>() {
            @Override
            protected ReplaceResult doInBackground() throws Exception {
                return SearchService.replaceInFiles(files, query, replacement, mc, ww, rx);
            }

            @Override
            protected void done() {
                try {
                    ReplaceResult result = get();
                    onReplaced.accept(result);
                    summary.setText(result.filesChanged() + " ファイル / "
                            + result.replacements() + " 件を置換しました");
                    if (result.replacements() > 0) {
                        runSearch();
                    } else {
                        replaceAllBtn.setEnabled(!lastGroups.isEmpty());
                    }
                } catch (Exception ex) {
                    summary.setText("置換失敗: " + ex.getMessage());
                    replaceAllBtn.setEnabled(!lastGroups.isEmpty());
                    JOptionPane.showMessageDialog(SearchSidebar.this,
                            ex.getMessage(), "すべて置換", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setResultActionsEnabled(boolean enabled) {
        tableViewBtn.setEnabled(enabled);
        editorViewBtn.setEnabled(enabled);
    }

    private void openResults(boolean table) {
        if (lastGroups.isEmpty()) {
            return;
        }
        String title = "Search: " + (lastQuery.isBlank() ? "(empty)" : truncateLine(lastQuery, 40));
        int context = (Integer) contextSpinner.getValue();
        if (table) {
            onOpenTable.accept(new SearchTableRequest(
                    title,
                    flatMatches,
                    context,
                    sessionKey,
                    lastQuery,
                    matchCase.isSelected(),
                    wholeWord.isSelected(),
                    regex.isSelected()
            ));
            return;
        }
        String report = SearchReportBuilder.build(lastQuery, workspaceRoot.get(), lastGroups, context);
        onOpenInEditor.accept(new SearchEditorRequest(
                title,
                report,
                lastQuery,
                matchCase.isSelected(),
                wholeWord.isSelected(),
                regex.isSelected(),
                sessionKey
        ));
    }

    private void clearResults() {
        clearResultsKeepingHistory();
    }

    private void clearResultsKeepingHistory() {
        lastGroups = List.of();
        flatMatches = List.of();
        lastQuery = "";
        fileNodes.clear();
        setResultActionsEnabled(false);
        replaceAllBtn.setEnabled(false);
        rootNode.removeAllChildren();
        treeModel.reload(rootNode);
        resultRows.clear();
    }

    private void expandAll() {
        // reload() on an empty root collapses it. With the root hidden, a collapsed
        // root has no visible rows, so expandRow never sees the files that were added later.
        if (rootNode.getChildCount() > 0) {
            resultsTree.expandPath(new TreePath(rootNode));
        }
        for (int i = 0; i < resultsTree.getRowCount(); i++) {
            resultsTree.expandRow(i);
        }
    }

    /** Appends hits the same way a running search does. Used by tests. */
    void appendMatches(List<SearchMatch> matches) {
        ingest(matches);
    }

    /** Clears the result tree the same way a new search does. Used by tests. */
    void resetResultTree() {
        clearResultsKeepingHistory();
    }

    void expandAllResults() {
        expandAll();
    }

    int visibleResultRows() {
        return resultsTree.getRowCount();
    }

    int modelFileCount() {
        return rootNode.getChildCount();
    }

    private void collapseAll() {
        for (int i = resultsTree.getRowCount() - 1; i >= 0; i--) {
            resultsTree.collapseRow(i);
        }
    }

    private static List<SearchMatch> flattenMatches(List<SearchFileGroup> groups) {
        List<SearchMatch> flat = new ArrayList<>();
        for (SearchFileGroup group : groups) {
            flat.addAll(group.matches());
        }
        return List.copyOf(flat);
    }

    public record SearchEditorRequest(
            String title,
            String content,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String sessionKey
    ) {
    }

    public record SearchTableRequest(
            String title,
            List<SearchMatch> matches,
            int contextLines,
            String sessionKey,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) {
    }

    private final class ResultTreeRenderer extends DefaultTreeCellRenderer {

        private final FileRowPanel fileRow = new FileRowPanel();
        private final MatchRowPanel matchRow = new MatchRowPanel();

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
            setLeafIcon(null);

            if (!(value instanceof DefaultMutableTreeNode node)) {
                return this;
            }
            Object user = node.getUserObject();
            if (user instanceof SearchFileGroup group) {
                Path ws = workspaceRoot.get();
                fileRow.setData(group.fileName(), PathEllipsis.middle(group.relativeDir(ws), PATH_MAX), group.hitCount());
                fileRow.setSelected(sel);
                fileRow.setRowWidth(availableRowWidth(tree, node));
                return fileRow;
            }
            if (user instanceof SearchMatch match) {
                matchRow.setData(match, sel);
                matchRow.setRowWidth(availableRowWidth(tree, node));
                return matchRow;
            }
            setText(String.valueOf(user));
            return this;
        }

        private int availableRowWidth(JTree tree, DefaultMutableTreeNode node) {
            int treeWidth = tree.getVisibleRect().width;
            if (treeWidth <= 0) {
                treeWidth = tree.getWidth();
            }
            if (tree.getParent() instanceof javax.swing.JViewport viewport) {
                treeWidth = Math.max(treeWidth, viewport.getWidth());
            }
            int indent = Math.max(0, node.getLevel() - 1) * 16 + 22;
            int insets = tree.getInsets().left + tree.getInsets().right;
            return Math.max(80, treeWidth - indent - insets - 8);
        }
    }

    private static final class MatchRowPanel extends JPanel {
        private final JLabel label = new JLabel();
        private int rowWidth = 280;

        MatchRowPanel() {
            setLayout(new BorderLayout());
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 4));
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
            add(label, BorderLayout.CENTER);
        }

        void setRowWidth(int width) {
            this.rowWidth = Math.max(80, width);
        }

        void setData(SearchMatch match, boolean selected) {
            label.setText(formatMatchHtml(match, selected));
            Color bg = selected
                    ? javax.swing.UIManager.getColor("Tree.selectionBackground")
                    : javax.swing.UIManager.getColor("Tree.background");
            if (bg == null) {
                bg = selected ? new Color(60, 60, 60) : new Color(43, 43, 43);
            }
            setBackground(bg);
            setToolTipText(match.lineNumber() + ": " + match.lineText());
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.width = rowWidth;
            return d;
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
    }

    private static String formatMatchHtml(SearchMatch match, boolean selected) {
        String line = match.lineText() == null ? "" : match.lineText();
        int start = Math.max(0, match.startColumn() - 1);
        int end = Math.max(start, match.endColumn() - 1);

        // Drop leading indent for display, keep highlight columns in sync
        int lead = 0;
        while (lead < line.length() && (line.charAt(lead) == ' ' || line.charAt(lead) == '\t')) {
            lead++;
        }
        if (lead > 0) {
            line = line.substring(lead);
            start = Math.max(0, start - lead);
            end = Math.max(0, end - lead);
        }
        start = Math.min(line.length(), start);
        end = Math.max(start, Math.min(line.length(), end));

        final int max = 72;
        int windowStart = 0;
        int windowEnd = line.length();
        if (line.length() > max) {
            int pad = Math.max(8, (max - (end - start)) / 2);
            windowStart = Math.max(0, start - pad);
            windowEnd = Math.min(line.length(), windowStart + max);
            if (windowEnd - windowStart < max) {
                windowStart = Math.max(0, windowEnd - max);
            }
        }

        String before = line.substring(windowStart, start);
        String hit = line.substring(start, end);
        String after = line.substring(end, windowEnd);
        String prefix = windowStart > 0 ? "…" : "";
        String suffix = windowEnd < line.length() ? "…" : "";

        Color text = selected
                ? javax.swing.UIManager.getColor("Tree.selectionForeground")
                : javax.swing.UIManager.getColor("Tree.textForeground");
        if (text == null) {
            text = selected ? Color.WHITE : new Color(220, 220, 220);
        }
        Color muted = new Color(
                (text.getRed() * 2 + 140) / 3,
                (text.getGreen() * 2 + 140) / 3,
                (text.getBlue() * 2 + 140) / 3
        );
        Color hitBg = hitBackground(selected);
        Color hitFg = hitForeground(selected);

        return "<html><nobr>"
                + "<font color='" + toHex(muted) + "'>" + match.lineNumber() + ":&nbsp;&nbsp;</font>"
                + "<font color='" + toHex(text) + "'>" + escapeHtml(prefix + before) + "</font>"
                + "<span style='background-color:" + toHex(hitBg) + ";color:" + toHex(hitFg) + ";'>"
                + escapeHtml(hit)
                + "</span>"
                + "<font color='" + toHex(text) + "'>" + escapeHtml(after + suffix) + "</font>"
                + "</nobr></html>";
    }

    private static Color hitBackground(boolean selected) {
        if (ThemeManager.isDark()) {
            return selected ? new Color(180, 120, 20) : new Color(140, 100, 20);
        }
        return selected ? new Color(255, 200, 80) : new Color(255, 220, 120);
    }

    private static Color hitForeground(boolean selected) {
        if (ThemeManager.isDark()) {
            return selected ? Color.WHITE : new Color(255, 235, 180);
        }
        return new Color(40, 30, 0);
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace(" ", "&nbsp;");
    }

    private static final class FileRowPanel extends JPanel {
        private final JLabel nameLabel = new JLabel();
        private final JLabel pathLabel = new JLabel();
        private final JLabel hitsLabel = new JLabel();
        private int rowWidth = 280;

        FileRowPanel() {
            setLayout(new BorderLayout(8, 0));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 6));

            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 12.5f));
            pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
            hitsLabel.setFont(hitsLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
            hitsLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            pathLabel.setForeground(new Color(150, 150, 150));
            hitsLabel.setForeground(new Color(90, 90, 90));

            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
            left.setOpaque(false);
            left.add(nameLabel);
            left.add(Box.createRigidArea(new Dimension(8, 0)));
            left.add(pathLabel);

            hitsLabel.setPreferredSize(new Dimension(36, 16));
            hitsLabel.setMinimumSize(new Dimension(36, 16));

            add(left, BorderLayout.CENTER);
            add(hitsLabel, BorderLayout.EAST);
        }

        void setRowWidth(int width) {
            this.rowWidth = Math.max(80, width);
        }

        void setData(String fileName, String path, int hits) {
            nameLabel.setText(fileName);
            pathLabel.setText(path);
            pathLabel.setToolTipText(path);
            nameLabel.setToolTipText(fileName);
            hitsLabel.setText(String.valueOf(hits));
            setToolTipText(fileName + " — " + path + " (" + hits + ")");
        }

        void setSelected(boolean selected) {
            Color bg = selected
                    ? javax.swing.UIManager.getColor("Tree.selectionBackground")
                    : javax.swing.UIManager.getColor("Tree.background");
            Color fg = selected
                    ? javax.swing.UIManager.getColor("Tree.selectionForeground")
                    : javax.swing.UIManager.getColor("Tree.textForeground");
            if (bg == null) {
                bg = selected ? new Color(60, 60, 60) : new Color(43, 43, 43);
            }
            if (fg == null) {
                fg = selected ? Color.WHITE : new Color(220, 220, 220);
            }
            setBackground(bg);
            nameLabel.setForeground(fg);
            pathLabel.setForeground(selected ? fg.darker() : new Color(150, 150, 150));
            // Keep hit count high-contrast so it stays readable in both themes
            if (selected) {
                hitsLabel.setForeground(fg);
            } else {
                Color text = javax.swing.UIManager.getColor("Label.foreground");
                hitsLabel.setForeground(text != null ? text : new Color(40, 40, 40));
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.width = rowWidth;
            return d;
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
    }

    private final class ResultTableModel extends AbstractTableModel {
        private final String[] columns = {"No", "ファイル", "行", "ヒット文字列", "テキスト"};
        private final List<SearchMatch> rows = new ArrayList<>();

        void add(SearchMatch match) {
            rows.add(match);
        }

        void fireAdded(int from) {
            if (from < rows.size()) {
                fireTableRowsInserted(from, rows.size() - 1);
            }
        }

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        SearchMatch matchAt(int row) {
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
        public Object getValueAt(int rowIndex, int columnIndex) {
            SearchMatch match = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> rowIndex + 1;
                case 1 -> displayFile(match.file());
                case 2 -> match.lineNumber();
                case 3 -> hitString(match);
                case 4 -> lineCell(match.lineText());
                default -> "";
            };
        }

        private String displayFile(Path file) {
            if (file == null) {
                return "";
            }
            Path root = workspaceRoot.get();
            try {
                if (root != null && file.startsWith(root)) {
                    return root.relativize(file).toString().replace('\\', '/');
                }
            } catch (IllegalArgumentException ignored) {
                // different roots
            }
            return file.toString().replace('\\', '/');
        }
    }

    private static String lineCell(String text) {
        if (text == null) {
            return "";
        }
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Matched substring from 1-based exclusive end columns. */
    private static String hitString(SearchMatch match) {
        String line = match.lineText();
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        int start = Math.max(0, match.startColumn() - 1);
        int end = Math.min(line.length(), Math.max(start, match.endColumn() - 1));
        if (start >= end) {
            return "";
        }
        return line.substring(start, end)
                .replace('\t', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    private Path selectedResultPath() {
        Object last = resultsTree.getLastSelectedPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node)) {
            return null;
        }
        Object user = node.getUserObject();
        if (user instanceof SearchMatch match) {
            return match.file();
        }
        if (user instanceof SearchFileGroup group) {
            return group.file();
        }
        return null;
    }

    private static void setComboPlaceholder(JComboBox<String> combo, String placeholder) {
        Component editor = combo.getEditor().getEditorComponent();
        if (editor instanceof JTextComponent text) {
            text.putClientProperty("JTextField.placeholderText", placeholder);
        }
    }

    private static JPanel labeledRow(JLabel label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(label, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private static void equalizeLabelWidth(JLabel... labels) {
        int max = 0;
        for (JLabel label : labels) {
            max = Math.max(max, label.getPreferredSize().width);
        }
        for (JLabel label : labels) {
            Dimension size = new Dimension(max, label.getPreferredSize().height);
            label.setPreferredSize(size);
            label.setMinimumSize(size);
        }
    }

    private static String truncateLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static boolean isUnderGit(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (java.nio.file.Files.exists(current.resolve(".git"))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
