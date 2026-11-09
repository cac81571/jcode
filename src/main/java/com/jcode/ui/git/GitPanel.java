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
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
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
    private final GitLogPanel logPanel = new GitLogPanel();
    private final GitStashPanel stashPanel = new GitStashPanel();
    private final GitCommitFilesPanel filesPanel = new GitCommitFilesPanel();
    private JSplitPane bodySplit;
    private Runnable onRepositoryReset = () -> {
    };

    public GitPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        styleList(stagedList);
        styleList(changesList);
        logPanel.setOnStatus(msg -> statusLabel.setText(msg));
        logPanel.setOnReset(() -> {
            refreshChangesOnly();
            stashPanel.refresh();
            onRepositoryReset.run();
        });
        logPanel.setOnCommitSelected(entry -> {
            if (entry == null) {
                filesPanel.clearFiles();
            } else {
                filesPanel.showCommit(entry.id());
            }
        });
        filesPanel.setOnShowDiff(texts -> onShowDiff.accept(texts, false));
        filesPanel.setOnStatus(msg -> statusLabel.setText(msg));
        stashPanel.setOnStatus(msg -> statusLabel.setText(msg));
        stashPanel.setOnChanged(() -> {
            refreshChangesOnly();
            onRepositoryReset.run();
        });

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
        JButton switchBranch = new JButton("Switch");
        JButton newBranch = new JButton("Create");
        JButton deleteBranch = new JButton("Delete");
        JButton stage = new JButton("Stage");
        JButton unstage = new JButton("Unstage");
        JButton discard = new JButton("Discard");
        JButton commit = new JButton("Commit");

        refresh.addActionListener(e -> refresh());
        switchBranch.setToolTipText("ブランチを切り替え");
        newBranch.setToolTipText("現在の HEAD からブランチを作成して切り替え");
        deleteBranch.setToolTipText("ローカルブランチを削除（現在のブランチ以外）");
        switchBranch.addActionListener(e -> showSwitchBranchMenu(switchBranch));
        newBranch.addActionListener(e -> createBranch());
        deleteBranch.addActionListener(e -> deleteBranch());
        stage.addActionListener(e -> stageSelected());
        unstage.addActionListener(e -> unstageSelected());
        discard.addActionListener(e -> discardSelected());
        commit.addActionListener(e -> commit());
        initButton.setToolTipText("このフォルダを Git リポジトリとして初期化");
        updateInitButton();

        JPanel branchRowBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        branchRowBtns.add(refresh);
        branchRowBtns.add(initButton);

        JPanel branchRow = new JPanel(new BorderLayout(4, 0));
        branchRow.add(branchLabel, BorderLayout.CENTER);
        branchRow.add(branchRowBtns, BorderLayout.EAST);

        JPanel branchActions = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
        branchActions.add(new JLabel("BRANCH"));
        branchActions.add(switchBranch);
        branchActions.add(newBranch);
        branchActions.add(deleteBranch);

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(branchRow, BorderLayout.NORTH);
        header.add(branchActions, BorderLayout.CENTER);

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
        listSplit.setResizeWeight(0.4);
        lists.add(listSplit, BorderLayout.CENTER);

        JSplitPane changesAndStash = new JSplitPane(JSplitPane.VERTICAL_SPLIT, lists, stashPanel);
        changesAndStash.setResizeWeight(0.78);
        changesAndStash.setContinuousLayout(true);

        JSplitPane leftColumn = new JSplitPane(JSplitPane.VERTICAL_SPLIT, changesAndStash, logPanel);
        leftColumn.setResizeWeight(0.6);
        leftColumn.setContinuousLayout(true);

        JPanel commitBar = new JPanel(new BorderLayout(4, 4));
        commitBar.add(commitMessage, BorderLayout.CENTER);
        commitBar.add(commit, BorderLayout.EAST);
        commitMessage.putClientProperty("JTextField.placeholderText", "Message (Ctrl+Enter to commit)");

        // Left: branch → commit → STAGED/CHANGES/STASH/HISTORY
        JPanel leftNorth = new JPanel(new BorderLayout(0, 8));
        leftNorth.add(header, BorderLayout.NORTH);
        leftNorth.add(commitBar, BorderLayout.SOUTH);

        JPanel leftStack = new JPanel(new BorderLayout(0, 8));
        leftStack.add(leftNorth, BorderLayout.NORTH);
        leftStack.add(leftColumn, BorderLayout.CENTER);

        // FILES spans full height (aligned with branch row at top); ~equal width with left column
        bodySplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftStack, filesPanel);
        bodySplit.setResizeWeight(0.5);
        bodySplit.setContinuousLayout(true);

        add(bodySplit, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    /** Split left column and FILES roughly 50/50 (call after the Git tab is shown). */
    public void balanceColumns() {
        if (bodySplit == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (bodySplit.getWidth() <= 0) {
                SwingUtilities.invokeLater(this::balanceColumns);
                return;
            }
            bodySplit.setDividerLocation(0.5);
        });
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

    /** After Soft/Mixed/Hard reset — reload editors / explorer. */
    public void setOnRepositoryReset(Runnable onRepositoryReset) {
        this.onRepositoryReset = onRepositoryReset;
    }

    public void bind(GitService git) {
        if (this.git != null) {
            this.git.close();
        }
        this.git = git;
        logPanel.bind(git);
        stashPanel.bind(git);
        filesPanel.bind(git);
        updateInitButton();
        refresh();
    }

    public void clear() {
        if (git != null) {
            git.close();
            git = null;
        }
        stagedModel.clear();
        changesModel.clear();
        logPanel.clear();
        stashPanel.clear();
        filesPanel.clear();
        branchLabel.setText("No repository");
        statusLabel.setText(" ");
        updateInitButton();
    }

    private void updateInitButton() {
        boolean hasRepo = git != null && git.isRepository();
        initButton.setEnabled(!hasRepo);
        initButton.setToolTipText(hasRepo
                ? "すでに Git 管理されています"
                : "このフォルダを Git リポジトリとして初期化");
    }

    public void refresh() {
        if (git == null) {
            stagedModel.clear();
            changesModel.clear();
            logPanel.clear();
            stashPanel.clear();
            branchLabel.setText("No repository");
            updateInitButton();
            return;
        }
        refreshChangesOnly();
        logPanel.refresh();
        stashPanel.refresh();
    }

    private void refreshChangesOnly() {
        if (git == null) {
            stagedModel.clear();
            changesModel.clear();
            branchLabel.setText("No repository");
            updateInitButton();
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

    private void showSwitchBranchMenu(Component invoker) {
        if (git == null) {
            statusLabel.setText("リポジトリがありません");
            return;
        }
        try {
            String current = git.currentBranch();
            List<String> branches = git.listBranches();
            if (branches.isEmpty()) {
                statusLabel.setText("ブランチがありません");
                return;
            }
            JPopupMenu menu = new JPopupMenu();
            for (String branch : branches) {
                boolean active = branch.equals(current);
                JMenuItem item = new JMenuItem(active ? "● " + branch : "    " + branch);
                item.setEnabled(!active);
                item.addActionListener(e -> checkoutBranch(branch));
                menu.add(item);
            }
            menu.show(invoker, 0, invoker.getHeight());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void checkoutBranch(String branch) {
        if (git == null || branch == null) {
            return;
        }
        try {
            git.checkoutBranch(branch);
            refresh();
            onRepositoryReset.run();
            statusLabel.setText("切替: " + branch);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void createBranch() {
        if (git == null) {
            statusLabel.setText("リポジトリがありません");
            return;
        }
        String name = JOptionPane.showInputDialog(
                this,
                "新しいブランチ名（作成して切り替えます）:",
                "新規ブランチ",
                JOptionPane.PLAIN_MESSAGE
        );
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty()) {
            return;
        }
        try {
            git.createAndCheckoutBranch(name);
            refresh();
            onRepositoryReset.run();
            statusLabel.setText("作成して切替: " + name);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void deleteBranch() {
        if (git == null) {
            statusLabel.setText("リポジトリがありません");
            return;
        }
        try {
            String current = git.currentBranch();
            List<String> candidates = git.listBranches().stream()
                    .filter(b -> !b.equals(current))
                    .toList();
            if (candidates.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "削除できるブランチがありません。\n（現在のブランチ以外が必要です）",
                        "ブランチ削除", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Object selected = JOptionPane.showInputDialog(
                    this,
                    "削除するブランチを選択:",
                    "ブランチ削除",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    candidates.toArray(),
                    candidates.getFirst()
            );
            if (selected == null) {
                return;
            }
            String branch = selected.toString();
            int confirm = JOptionPane.showConfirmDialog(
                    this,
                    "ブランチ「" + branch + "」を削除しますか？",
                    "ブランチ削除",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (confirm != JOptionPane.OK_OPTION) {
                return;
            }
            try {
                git.deleteBranch(branch, false);
            } catch (Exception notMerged) {
                int force = JOptionPane.showConfirmDialog(
                        this,
                        "通常削除できませんでした:\n" + notMerged.getMessage()
                                + "\n\n強制削除しますか？（未マージのコミットが失われる可能性があります）",
                        "強制削除",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (force != JOptionPane.YES_OPTION) {
                    return;
                }
                git.deleteBranch(branch, true);
            }
            refresh();
            statusLabel.setText("削除: " + branch);
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
