package com.jcode.ui.git;

import com.jcode.model.DiffTexts;
import com.jcode.model.GitChange;
import com.jcode.service.GitService;
import com.jcode.ui.WrapLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Source Control view powered by JGit (EGit's engine).
 */
public final class GitPanel extends JPanel {

    private final DefaultListModel<GitChange> stagedModel = new DefaultListModel<>();
    private final DefaultListModel<GitChange> changesModel = new DefaultListModel<>();
    private final JList<GitChange> stagedList = new JList<>(stagedModel);
    private final JList<GitChange> changesList = new JList<>(changesModel);
    private final JTextField commitMessage = new JTextField();
    private final JLabel branchLabel = new JLabel("No repository");
    private final JLabel statusLabel = new JLabel(" ");

    private GitService git;
    private Consumer<Path> onOpenFile = p -> {
    };
    private Consumer<Path> onWorkingTreeChanged = p -> {
    };
    private BiConsumer<DiffTexts, Boolean> onShowDiff = (d, staged) -> {
    };
    private final JButton initButton = new JButton("Init Repo");

    public GitPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        styleList(stagedList);
        styleList(changesList);

        stagedList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                // Diff shows the lead (last clicked) item when multi-selecting
                showDiff(stagedList.getSelectedValue(), true);
            }
        });
        changesList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDiff(changesList.getSelectedValue(), false);
            }
        });
        installOpenOnDoubleClick(stagedList);
        installOpenOnDoubleClick(changesList);

        JButton refresh = new JButton("Refresh");
        JButton stage = new JButton("Stage");
        JButton unstage = new JButton("Unstage");
        JButton discard = new JButton("Discard");
        JButton commit = new JButton("Commit");

        refresh.addActionListener(e -> refresh());
        stage.addActionListener(e -> stageSelected());
        unstage.addActionListener(e -> unstageSelected());
        discard.addActionListener(e -> discardSelected());
        commit.addActionListener(e -> commit());
        initButton.addActionListener(e ->
                statusLabel.setText("Use File → Open Folder on a git repo, or Init below after open"));

        JPanel header = new JPanel(new BorderLayout());
        header.add(branchLabel, BorderLayout.WEST);
        JPanel headerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        headerBtns.add(refresh);
        headerBtns.add(initButton);
        header.add(headerBtns, BorderLayout.EAST);

        JPanel stagedHeader = labeled("STAGED", unstage);
        JPanel changesHeader = labeled("CHANGES", stage, discard);

        JPanel lists = new JPanel(new BorderLayout(0, 8));
        JPanel stagedPanel = new JPanel(new BorderLayout());
        stagedPanel.add(stagedHeader, BorderLayout.NORTH);
        stagedPanel.add(new JScrollPane(stagedList), BorderLayout.CENTER);

        JPanel changesPanel = new JPanel(new BorderLayout());
        changesPanel.add(changesHeader, BorderLayout.NORTH);
        changesPanel.add(new JScrollPane(changesList), BorderLayout.CENTER);

        JSplitPane listSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stagedPanel, changesPanel);
        listSplit.setResizeWeight(0.35);
        lists.add(listSplit, BorderLayout.CENTER);

        JPanel commitBar = new JPanel(new BorderLayout(4, 4));
        commitBar.add(commitMessage, BorderLayout.CENTER);
        commitBar.add(commit, BorderLayout.EAST);
        commitMessage.putClientProperty("JTextField.placeholderText", "Message (Ctrl+Enter to commit)");

        // Commit above STAGED: header → commit → staged/changes lists
        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.add(header, BorderLayout.NORTH);
        north.add(commitBar, BorderLayout.SOUTH);

        add(north, BorderLayout.NORTH);
        add(lists, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    public JButton getInitButton() {
        return initButton;
    }

    public void setOnOpenFile(Consumer<Path> onOpenFile) {
        this.onOpenFile = onOpenFile;
    }

    /** Called after discard so editors / explorer can reload. */
    public void setOnWorkingTreeChanged(Consumer<Path> onWorkingTreeChanged) {
        this.onWorkingTreeChanged = onWorkingTreeChanged;
    }

    /** Show colored diff in the main (right) editor area. Second arg = staged. */
    public void setOnShowDiff(BiConsumer<DiffTexts, Boolean> onShowDiff) {
        this.onShowDiff = onShowDiff;
    }

    public void bind(GitService git) {
        if (this.git != null) {
            this.git.close();
        }
        this.git = git;
        refresh();
    }

    public void clear() {
        if (git != null) {
            git.close();
            git = null;
        }
        stagedModel.clear();
        changesModel.clear();
        branchLabel.setText("No repository");
        statusLabel.setText(" ");
    }

    public void refresh() {
        if (git == null) {
            stagedModel.clear();
            changesModel.clear();
            branchLabel.setText("No repository");
            return;
        }

        statusLabel.setText("Refreshing…");
        new SwingWorker<RefreshData, Void>() {
            @Override
            protected RefreshData doInBackground() throws Exception {
                String branch = git.currentBranch();
                List<GitChange> changes = git.listChanges();
                return new RefreshData(branch, changes);
            }

            @Override
            protected void done() {
                try {
                    RefreshData data = get();
                    stagedModel.clear();
                    changesModel.clear();
                    for (GitChange change : data.changes()) {
                        if (change.staged()) {
                            stagedModel.addElement(change);
                        } else {
                            changesModel.addElement(change);
                        }
                    }
                    branchLabel.setText("branch " + data.branch()
                            + "  (" + stagedModel.size() + " staged, "
                            + changesModel.size() + " changes)");
                    statusLabel.setText(" ");
                } catch (Exception ex) {
                    statusLabel.setText("Git error: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void stageSelected() {
        List<GitChange> selected = changesList.getSelectedValuesList();
        if (git == null || selected.isEmpty()) {
            statusLabel.setText("CHANGES からファイルを選択してください");
            return;
        }
        try {
            for (GitChange change : selected) {
                git.stage(change.path());
            }
            refresh();
            statusLabel.setText("Staged " + selected.size() + " file(s)");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void unstageSelected() {
        List<GitChange> selected = stagedList.getSelectedValuesList();
        if (git == null || selected.isEmpty()) {
            statusLabel.setText("STAGED からファイルを選択してください");
            return;
        }
        try {
            for (GitChange change : selected) {
                git.unstage(change.path());
            }
            refresh();
            statusLabel.setText("Unstaged " + selected.size() + " file(s)");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void discardSelected() {
        List<GitChange> selected = changesList.getSelectedValuesList();
        if (git == null || selected.isEmpty()) {
            statusLabel.setText("CHANGES からファイルを選択してください");
            return;
        }
        String message = selected.size() == 1
                ? selected.getFirst().path() + "\nの変更を破棄しますか？\n（元に戻せません）"
                : selected.size() + " 件の変更を破棄しますか？\n（元に戻せません）";
        int choice = JOptionPane.showConfirmDialog(
                this,
                message,
                "Discard Changes",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            for (GitChange change : selected) {
                Path absolute = git.workspaceRoot().resolve(change.path());
                git.discard(change.path());
                onWorkingTreeChanged.accept(absolute);
            }
            refresh();
            statusLabel.setText("Discarded " + selected.size() + " file(s)");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void commit() {
        if (git == null) {
            return;
        }
        String message = commitMessage.getText().trim();
        if (message.isEmpty()) {
            statusLabel.setText("Enter a commit message");
            return;
        }
        try {
            git.commit(message);
            commitMessage.setText("");
            refresh();
            statusLabel.setText("Committed");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void installOpenOnDoubleClick(JList<GitChange> list) {
        list.setToolTipText("ダブルクリックでファイルを開く");
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || git == null) {
                    return;
                }
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                GitChange change = list.getModel().getElementAt(index);
                if (change != null) {
                    onOpenFile.accept(git.workspaceRoot().resolve(change.path()));
                }
            }
        });
    }

    private void showDiff(GitChange change, boolean staged) {
        if (git == null || change == null) {
            return;
        }
        try {
            DiffTexts texts = git.loadDiffTexts(change.path(), staged);
            onShowDiff.accept(texts, staged);
            statusLabel.setText((staged ? "Staged" : "Changes") + ": " + change.path());
        } catch (Exception ex) {
            statusLabel.setText("Diff error: " + ex.getMessage());
        }
    }

    private void showError(Exception ex) {
        statusLabel.setText(ex.getMessage());
        JOptionPane.showMessageDialog(this, ex.getMessage(), "Git", JOptionPane.ERROR_MESSAGE);
    }

    private static JPanel labeled(String title, JButton... buttons) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.add(new JLabel(title), BorderLayout.NORTH);
        JPanel btns = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        for (JButton b : buttons) {
            btns.add(b);
        }
        p.add(btns, BorderLayout.CENTER);
        return p;
    }

    private static void styleList(JList<GitChange> list) {
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus
            ) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof GitChange change) {
                    setText(change.statusLabel() + "  " + change.path());
                }
                return this;
            }
        });
    }

    private record RefreshData(String branch, List<GitChange> changes) {
    }
}
