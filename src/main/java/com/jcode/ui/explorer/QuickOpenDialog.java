package com.jcode.ui.explorer;

import com.jcode.service.SearchHistory;
import com.jcode.service.WorkspaceFiles;
import com.jcode.ui.UiFonts;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * VS Code-like Quick Open: partial path match over workspace files.
 */
public final class QuickOpenDialog extends JDialog {

    private static final int RESULT_LIMIT = 80;

    private final JTextField queryField = new JTextField();
    private final DefaultComboBoxModel<String> folderHistory = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<String> extensionHistory = new DefaultComboBoxModel<>();
    private final JComboBox<String> folderField = new JComboBox<>(folderHistory);
    private final JComboBox<String> extensionField = new JComboBox<>(extensionHistory);
    private final DefaultListModel<WorkspaceFiles.Hit> model = new DefaultListModel<>();
    private final JList<WorkspaceFiles.Hit> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");
    private final Path workspaceRoot;
    private final Consumer<Path> onOpen;

    private List<Path> indexed = List.of();
    private int filterSeq;

    public QuickOpenDialog(Frame owner, Path workspaceRoot, Consumer<Path> onOpen) {
        super(owner, "ファイルを開く", true);
        this.workspaceRoot = workspaceRoot;
        this.onOpen = onOpen;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(640, 420);
        setLocationRelativeTo(owner);
        setMinimumSize(new Dimension(420, 280));

        queryField.putClientProperty("JTextField.placeholderText", "ファイル名の一部を入力…");
        queryField.setFont(UiFonts.ui(14f));
        configureHistoryCombo(
                folderField,
                folderHistory,
                SearchHistory.Kind.QUICK_OPEN_FOLDER,
                "例: src/main, com/jcode",
                "相対パスに含まれるフォルダ。カンマ区切りで複数（いずれかに一致）。履歴から選択可",
                "フォルダ履歴をクリア");
        configureHistoryCombo(
                extensionField,
                extensionHistory,
                SearchHistory.Kind.QUICK_OPEN_EXTENSION,
                "例: java, xml",
                "拡張子。カンマ区切りで複数（ドットは不要）。履歴から選択可",
                "拡張子履歴をクリア");
        showLastValue(folderField, SearchHistory.Kind.QUICK_OPEN_FOLDER);
        showLastValue(extensionField, SearchHistory.Kind.QUICK_OPEN_EXTENSION);
        extensionField.setPreferredSize(new Dimension(180, extensionField.getPreferredSize().height));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(UiFonts.ui(13f));
        list.setCellRenderer(new HitRenderer());
        list.setFixedCellHeight(28);

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setFont(UiFonts.ui(11.5f));

        JPanel folderRow = labeledField("フォルダ", folderField);
        JPanel extensionRow = labeledField("拡張子", extensionField);
        JPanel filters = new JPanel(new BorderLayout(8, 0));
        filters.add(folderRow, BorderLayout.CENTER);
        filters.add(extensionRow, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        north.add(queryField, BorderLayout.NORTH);
        north.add(filters, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(north, BorderLayout.NORTH);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);

        bindKeys();
        watchText(queryField);
        watchText(editorOf(folderField));
        watchText(editorOf(extensionField));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });

        indexFiles();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                queryField.requestFocusInWindow();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                SearchHistory.rememberLast(SearchHistory.Kind.QUICK_OPEN_FOLDER, comboText(folderField));
                SearchHistory.rememberLast(SearchHistory.Kind.QUICK_OPEN_EXTENSION, comboText(extensionField));
            }
        });
    }

    private void bindKeys() {
        JComponent content = (JComponent) getContentPane();
        content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        content.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

    }

    private KeyAdapter listNavigationKeys() {
        return new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int size = model.getSize();
                if (size == 0) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        dispose();
                    }
                    return;
                }
                int idx = list.getSelectedIndex();
                if (folderField.isPopupVisible() || extensionField.isPopupVisible()) {
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        list.setSelectedIndex(Math.min(size - 1, Math.max(0, idx) + 1));
                        list.ensureIndexIsVisible(list.getSelectedIndex());
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        list.setSelectedIndex(Math.max(0, (idx < 0 ? 0 : idx) - 1));
                        list.ensureIndexIsVisible(list.getSelectedIndex());
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER -> {
                        openSelected();
                        e.consume();
                    }
                    default -> {
                    }
                }
            }
        };
    }

    private static JPanel labeledField(String labelText, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(new JLabel(labelText), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private void configureHistoryCombo(
            JComboBox<String> combo,
            DefaultComboBoxModel<String> model,
            SearchHistory.Kind kind,
            String placeholder,
            String tip,
            String clearLabel
    ) {
        combo.setEditable(true);
        combo.setToolTipText(tip);
        reloadHistory(combo, model, kind);
        JTextComponent editor = editorOf(combo);
        editor.putClientProperty("JTextField.placeholderText", placeholder);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem clear = new JMenuItem(clearLabel);
        clear.addActionListener(e -> {
            SearchHistory.clear(kind);
            reloadHistory(combo, model, kind);
            status.setText(clearLabel.replace("をクリア", "をクリアしました"));
        });
        menu.add(clear);
        combo.setComponentPopupMenu(menu);
        editor.setComponentPopupMenu(menu);
    }

    private void watchText(JTextComponent field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleFilter();
            }
        });
        field.addKeyListener(listNavigationKeys());
    }

    private static void reloadHistory(
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

    private static void showLastValue(JComboBox<String> combo, SearchHistory.Kind kind) {
        combo.getEditor().setItem(SearchHistory.last(kind));
    }

    private static JTextComponent editorOf(JComboBox<String> combo) {
        return (JTextComponent) combo.getEditor().getEditorComponent();
    }

    private static String comboText(JComboBox<String> combo) {
        Object item = combo.getEditor().getItem();
        if (item == null) {
            item = combo.getSelectedItem();
        }
        return item == null ? "" : item.toString();
    }

    private void indexFiles() {
        status.setText("ファイル一覧を収集中…");
        new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                return WorkspaceFiles.listFiles(workspaceRoot);
            }

            @Override
            protected void done() {
                try {
                    indexed = get();
                    status.setText(indexed.size() + " ファイル");
                    scheduleFilter();
                } catch (Exception ex) {
                    status.setText("一覧取得に失敗: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void scheduleFilter() {
        int seq = ++filterSeq;
        String query = queryField.getText();
        String folders = comboText(folderField);
        String extensions = comboText(extensionField);
        SwingUtilities.invokeLater(() -> {
            if (seq != filterSeq) {
                return;
            }
            List<WorkspaceFiles.Hit> hits = WorkspaceFiles.filter(
                    workspaceRoot, indexed, query, folders, extensions, RESULT_LIMIT);
            model.clear();
            for (WorkspaceFiles.Hit hit : hits) {
                model.addElement(hit);
            }
            if (!hits.isEmpty()) {
                list.setSelectedIndex(0);
            }
            String shown = hits.size() + (hits.size() >= RESULT_LIMIT ? "+" : "") + " 件";
            String scope = scopeText(folders, extensions);
            if (query.isBlank() && scope.isEmpty()) {
                status.setText(indexed.size() + " ファイル · 絞り込みなし");
            } else if (query.isBlank()) {
                status.setText(shown + " · " + scope);
            } else {
                status.setText(shown + " · 「" + query.trim() + "」" + (scope.isEmpty() ? "" : " · " + scope));
            }
        });
    }

    private static String scopeText(String folders, String extensions) {
        String folder = folders == null ? "" : folders.trim();
        String extension = extensions == null ? "" : extensions.trim();
        if (folder.isEmpty() && extension.isEmpty()) {
            return "";
        }
        if (folder.isEmpty()) {
            return "拡張子 " + extension;
        }
        if (extension.isEmpty()) {
            return "フォルダ " + folder;
        }
        return "フォルダ " + folder + " · 拡張子 " + extension;
    }

    private void openSelected() {
        WorkspaceFiles.Hit hit = list.getSelectedValue();
        if (hit == null && model.getSize() > 0) {
            hit = model.getElementAt(0);
        }
        if (hit == null) {
            return;
        }
        dispose();
        onOpen.accept(hit.file());
    }

    private static final class HitRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof WorkspaceFiles.Hit hit) {
                String dir = hit.directory();
                label.setText(dir.isEmpty()
                        ? hit.fileName()
                        : hit.fileName() + "  —  " + dir);
                label.setToolTipText(hit.relative());
                Font base = label.getFont();
                label.setFont(base.deriveFont(Font.PLAIN, 13f));
            }
            return label;
        }
    }
}
