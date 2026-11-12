package com.jcode.ui.search;

import com.jcode.model.ReplaceResult;
import com.jcode.model.SearchFileGroup;
import com.jcode.model.SearchMatch;
import com.jcode.service.FilePathFilter;
import com.jcode.service.SearchHistory;
import com.jcode.service.SearchReportBuilder;
import com.jcode.service.SearchService;
import com.jcode.ui.ClipboardPaths;
import com.jcode.ui.ThemeManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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
 * File rows: {@code filename   path…   hits}
 */
public final class SearchSidebar extends JPanel {

    private static final int PATH_MAX = 36;

    private final DefaultComboBoxModel<String> historyModel = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<String> includeHistoryModel = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<String> excludeHistoryModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> queryField = new JComboBox<>(historyModel);
    private final JTextField replaceField = new JTextField();
    private final JComboBox<String> includeField = new JComboBox<>(includeHistoryModel);
    private final JComboBox<String> excludeField = new JComboBox<>(excludeHistoryModel);
    private final JCheckBox matchCase = new JCheckBox("大文字小文字を区別");
    private final JCheckBox wholeWord = new JCheckBox("単語単位");
    private final JCheckBox regex = new JCheckBox("正規表現");
    private final JCheckBox openFileOnly = new JCheckBox("開いているファイルのみ");
    private final JSpinner contextSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 20, 1));
    private final JLabel summary = new JLabel(" ");
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("results");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree results = new JTree(treeModel);
    private final JButton openInEditorBtn = new JButton("エディタで開く");
    private final JButton replaceAllBtn = new JButton("すべて置換");
    private final Supplier<Path> workspaceRoot;
    private Supplier<ActiveFile> activeFile = () -> null;
    private BiConsumer<Path, SearchMatch> onOpenMatch = (p, m) -> {
    };
    private Consumer<SearchEditorRequest> onOpenInEditor = r -> {
    };
    private Consumer<ReplaceResult> onReplaced = r -> {
    };

    /** Currently focused editor file (disk path + buffer text, including unsaved edits). */
    public record ActiveFile(Path path, String text) {
    }

    private List<SearchFileGroup> lastGroups = List.of();
    private String lastQuery = "";
    /** Incremented on each search; stale SwingWorkers ignore their done(). */
    private int searchGeneration;
    /** Skip Include/Exclude auto-search while history models are updated. */
    private boolean suppressFilterAutoSearch;

    public SearchSidebar(Supplier<Path> workspaceRoot) {
        super(new BorderLayout(0, 8));
        this.workspaceRoot = workspaceRoot;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        matchCase.setToolTipText("大文字と小文字を区別して検索");
        wholeWord.setToolTipText("単語単位で一致");
        regex.setToolTipText("正規表現で検索");
        openFileOnly.setToolTipText("エディタで選択中のファイルだけを検索（未保存の内容も対象）。Include / Exclude も適用");
        contextSpinner.setToolTipText("エディタ表示時の前後行数 (±N)。サイドバーには出ません");
        ((JSpinner.DefaultEditor) contextSpinner.getEditor()).getTextField().setColumns(2);

        queryField.setEditable(true);
        queryField.setToolTipText("検索キーワード（履歴から選択可 / 右クリックで履歴クリア）");
        includeField.setEditable(true);
        excludeField.setEditable(true);
        includeField.setToolTipText("files to include（カンマ区切りの glob。空ならすべて / 履歴あり）");
        excludeField.setToolTipText("files to exclude（カンマ区切りの glob / 履歴あり）");
        setComboPlaceholder(includeField, "例: *.java, src/**");
        setComboPlaceholder(excludeField, "例: **/test/**, *.min.js");
        reloadHistoryModel();
        reloadFilterHistoryModels();
        installHistoryPopup(queryField, SearchHistory.Kind.QUERY, "検索履歴をクリア");
        installHistoryPopup(includeField, SearchHistory.Kind.INCLUDE, "Include 履歴をクリア");
        installHistoryPopup(excludeField, SearchHistory.Kind.EXCLUDE, "Exclude 履歴をクリア");
        installFilterAutoSearch(includeField);
        installFilterAutoSearch(excludeField);

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        opts.add(matchCase);
        opts.add(wholeWord);
        opts.add(regex);

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.add(new JLabel("SEARCH"), BorderLayout.WEST);
        titleRow.add(opts, BorderLayout.EAST);

        replaceField.putClientProperty("JTextField.placeholderText", "置換文字列");
        replaceField.setToolTipText("置換後の文字列");

        JButton searchBtn = new JButton("検索");
        searchBtn.addActionListener(e -> runSearch());

        replaceAllBtn.setEnabled(false);
        replaceAllBtn.setToolTipText("検索結果のファイルすべてで置換（要確認）");
        replaceAllBtn.addActionListener(e -> runReplaceAll());
        equalizeButtonWidth(searchBtn, replaceAllBtn);

        JButton collapseAllBtn = new JButton("全て折りたたむ");
        JButton expandAllBtn = new JButton("全て展開");
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

        JLabel includeLabel = new JLabel("Include");
        JLabel excludeLabel = new JLabel("Exclude");
        equalizeLabelWidth(includeLabel, excludeLabel);

        JPanel includeRow = labeledRow(includeLabel, includeField);
        JPanel excludeRow = labeledRow(excludeLabel, excludeField);

        openInEditorBtn.setEnabled(false);
        openInEditorBtn.setToolTipText("検索結果をコピー可能なテキストとしてエディタに開く（前後 N 行を反映）");
        openInEditorBtn.addActionListener(e -> openResultsInEditor());

        JPanel editorActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        editorActions.add(new JLabel("前後"));
        editorActions.add(contextSpinner);
        editorActions.add(new JLabel("行"));
        editorActions.add(openInEditorBtn);

        JPanel foldBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        foldBtns.add(expandAllBtn);
        foldBtns.add(collapseAllBtn);

        JPanel scopeRow = new JPanel(new BorderLayout(4, 0));
        scopeRow.add(openFileOnly, BorderLayout.WEST);
        scopeRow.add(editorActions, BorderLayout.EAST);

        JPanel filterBlock = new JPanel();
        filterBlock.setLayout(new BoxLayout(filterBlock, BoxLayout.Y_AXIS));
        includeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        excludeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        scopeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterBlock.add(includeRow);
        filterBlock.add(Box.createVerticalStrut(4));
        filterBlock.add(excludeRow);
        filterBlock.add(Box.createVerticalStrut(4));
        filterBlock.add(scopeRow);

        JPanel mid = new JPanel(new BorderLayout(4, 4));
        mid.add(replaceRow, BorderLayout.NORTH);
        mid.add(filterBlock, BorderLayout.CENTER);

        JPanel fields = new JPanel(new BorderLayout(4, 4));
        fields.add(queryRow, BorderLayout.NORTH);
        fields.add(mid, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout(4, 4));
        north.add(titleRow, BorderLayout.NORTH);
        north.add(fields, BorderLayout.CENTER);

        results.setRootVisible(false);
        results.setShowsRootHandles(true);
        results.setRowHeight(0);
        results.setCellRenderer(new ResultTreeRenderer());
        results.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                results.repaint();
            }
        });
        results.addTreeSelectionListener(e -> {
            Object last = results.getLastSelectedPathComponent();
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
        ClipboardPaths.install(results, this::selectedResultPath);

        // Enter in the editor, or choosing a history item
        queryField.addActionListener(e -> runSearch());

        JPanel summaryRow = new JPanel(new BorderLayout(4, 0));
        summaryRow.add(summary, BorderLayout.CENTER);
        summaryRow.add(foldBtns, BorderLayout.EAST);

        JPanel resultsArea = new JPanel(new BorderLayout(0, 4));
        resultsArea.add(summaryRow, BorderLayout.NORTH);
        resultsArea.add(new JScrollPane(results), BorderLayout.CENTER);

        add(north, BorderLayout.NORTH);
        add(resultsArea, BorderLayout.CENTER);
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

    public void setOnReplaced(Consumer<ReplaceResult> onReplaced) {
        this.onReplaced = onReplaced;
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

    private void reloadFilterHistoryModels() {
        reloadComboHistory(includeField, includeHistoryModel, SearchHistory.Kind.INCLUDE);
        reloadComboHistory(excludeField, excludeHistoryModel, SearchHistory.Kind.EXCLUDE);
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

    private void runSearch() {
        String query = currentQuery().trim();
        if (query.isEmpty()) {
            clearResults();
            summary.setText(" ");
            return;
        }

        boolean onlyOpen = openFileOnly.isSelected();
        ActiveFile open = onlyOpen ? activeFile.get() : null;
        Path root = workspaceRoot.get();
        if (onlyOpen) {
            if (open == null || open.path() == null) {
                clearResults();
                summary.setText("開いているファイルがありません");
                openInEditorBtn.setEnabled(false);
                replaceAllBtn.setEnabled(false);
                return;
            }
        } else if (root == null) {
            summary.setText("フォルダを開いてください");
            return;
        }

        String include = comboText(includeField).trim();
        String exclude = comboText(excludeField).trim();

        suppressFilterAutoSearch = true;
        try {
            SearchHistory.remember(query);
            SearchHistory.remember(SearchHistory.Kind.INCLUDE, include);
            SearchHistory.remember(SearchHistory.Kind.EXCLUDE, exclude);
            reloadHistoryModel();
            reloadFilterHistoryModels();
            queryField.getEditor().setItem(query);
            includeField.getEditor().setItem(include);
            excludeField.getEditor().setItem(exclude);
        } finally {
            suppressFilterAutoSearch = false;
        }

        final int generation = ++searchGeneration;
        summary.setText("検索中…");
        clearResultsKeepingHistory();
        boolean mc = matchCase.isSelected();
        boolean ww = wholeWord.isSelected();
        boolean rx = regex.isSelected();
        final ActiveFile target = open;
        final Path searchRoot = root;

        new SwingWorker<List<SearchFileGroup>, Void>() {
            @Override
            protected List<SearchFileGroup> doInBackground() throws Exception {
                if (onlyOpen) {
                    Path filterRoot = searchRoot != null
                            ? searchRoot
                            : (target.path().getParent() != null ? target.path().getParent() : target.path());
                    FilePathFilter filter = FilePathFilter.parse(include, exclude);
                    if (!filter.accept(filterRoot, target.path())) {
                        return List.of();
                    }
                    return groupByFile(SearchService.findInText(
                            target.text(), target.path(), query, mc, ww, rx));
                }
                return groupByFile(SearchService.findInWorkspace(
                        searchRoot, query, mc, ww, rx, include, exclude));
            }

            @Override
            protected void done() {
                if (generation != searchGeneration) {
                    return;
                }
                try {
                    List<SearchFileGroup> groups = get();
                    lastGroups = groups;
                    lastQuery = query;
                    rebuildTree(groups);
                    int hits = groups.stream().mapToInt(SearchFileGroup::hitCount).sum();
                    summary.setText(groups.size() + " ファイル · " + hits + " 件");
                    openInEditorBtn.setEnabled(!groups.isEmpty());
                    replaceAllBtn.setEnabled(!groups.isEmpty());
                } catch (Exception ex) {
                    if (generation != searchGeneration) {
                        return;
                    }
                    summary.setText("検索失敗: " + ex.getMessage());
                    openInEditorBtn.setEnabled(false);
                    replaceAllBtn.setEnabled(false);
                }
            }
        }.execute();
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

    private void openResultsInEditor() {
        if (lastGroups.isEmpty()) {
            return;
        }
        int context = (Integer) contextSpinner.getValue();
        String report = SearchReportBuilder.build(lastQuery, workspaceRoot.get(), lastGroups, context);
        String title = "Search: " + (lastQuery.isBlank() ? "(empty)" : truncateLine(lastQuery, 40));
        onOpenInEditor.accept(new SearchEditorRequest(
                title,
                report,
                lastQuery,
                matchCase.isSelected(),
                wholeWord.isSelected(),
                regex.isSelected()
        ));
    }

    private void rebuildTree(List<SearchFileGroup> groups) {
        rootNode.removeAllChildren();
        // Sidebar shows hit lines only; ±N context is for the editor report.
        for (SearchFileGroup group : groups) {
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(group);
            for (SearchMatch match : group.matches()) {
                fileNode.add(new DefaultMutableTreeNode(match));
            }
            rootNode.add(fileNode);
        }
        treeModel.reload(rootNode);
        expandAll();
    }

    private void clearResults() {
        clearResultsKeepingHistory();
    }

    private void clearResultsKeepingHistory() {
        lastGroups = List.of();
        lastQuery = "";
        openInEditorBtn.setEnabled(false);
        replaceAllBtn.setEnabled(false);
        rootNode.removeAllChildren();
        treeModel.reload(rootNode);
    }

    private void expandAll() {
        for (int i = 0; i < results.getRowCount(); i++) {
            results.expandRow(i);
        }
    }

    private void collapseAll() {
        for (int i = results.getRowCount() - 1; i >= 0; i--) {
            results.collapseRow(i);
        }
    }

    private static List<SearchFileGroup> groupByFile(List<SearchMatch> matches) {
        Map<Path, List<SearchMatch>> map = new LinkedHashMap<>();
        for (SearchMatch match : matches) {
            map.computeIfAbsent(match.file(), k -> new ArrayList<>()).add(match);
        }
        List<SearchFileGroup> groups = new ArrayList<>(map.size());
        map.forEach((file, list) -> groups.add(new SearchFileGroup(file, List.copyOf(list))));
        return groups;
    }

    public record SearchEditorRequest(
            String title,
            String content,
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

    private Path selectedResultPath() {
        Object last = results.getLastSelectedPathComponent();
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

    private void installFilterAutoSearch(JComboBox<String> field) {
        // Enter in the editor only — avoid combo ActionEvent (fires on history reload / setItem)
        Component editor = field.getEditor().getEditorComponent();
        if (editor instanceof JTextField textField) {
            textField.addActionListener(e -> {
                if (!suppressFilterAutoSearch) {
                    runSearch();
                }
            });
        }
        editor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (!e.isTemporary() && !suppressFilterAutoSearch) {
                    runSearch();
                }
            }
        });
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

    private static void equalizeButtonWidth(JButton... buttons) {
        int maxWidth = 0;
        for (JButton button : buttons) {
            maxWidth = Math.max(maxWidth, button.getPreferredSize().width);
        }
        for (JButton button : buttons) {
            Dimension size = button.getPreferredSize();
            Dimension equal = new Dimension(maxWidth, size.height);
            button.setPreferredSize(equal);
            button.setMinimumSize(equal);
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
