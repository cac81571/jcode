package com.jcode.ui.explorer;

import com.jcode.service.WorkspaceFiles;
import com.jcode.ui.UiFonts;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
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

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(UiFonts.ui(13f));
        list.setCellRenderer(new HitRenderer());
        list.setFixedCellHeight(28);

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.setFont(UiFonts.ui(11.5f));

        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        north.add(queryField, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(north, BorderLayout.NORTH);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);

        bindKeys();
        queryField.getDocument().addDocumentListener(new DocumentListener() {
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

        queryField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int size = model.getSize();
                if (size == 0) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                            dispose();
                        }
                    }
                    return;
                }
                int idx = list.getSelectedIndex();
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
        });
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
        SwingUtilities.invokeLater(() -> {
            if (seq != filterSeq) {
                return;
            }
            List<WorkspaceFiles.Hit> hits = WorkspaceFiles.filter(workspaceRoot, indexed, query, RESULT_LIMIT);
            model.clear();
            for (WorkspaceFiles.Hit hit : hits) {
                model.addElement(hit);
            }
            if (!hits.isEmpty()) {
                list.setSelectedIndex(0);
            }
            if (query.isBlank()) {
                status.setText(indexed.size() + " ファイル · 絞り込みなし");
            } else {
                status.setText(hits.size() + (hits.size() >= RESULT_LIMIT ? "+" : "")
                        + " 件 · 「" + query.trim() + "」");
            }
        });
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
