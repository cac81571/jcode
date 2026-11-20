package com.jcode.ui.git;

import com.jcode.model.DiffTexts;
import com.jcode.model.GitChange;
import com.jcode.service.GitService;
import com.jcode.ui.WrapLayout;
import com.jcode.ui.PathEllipsis;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    private JSplitPane historyFilesSplit;
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
                return;
            }
            stashPanel.clearSelection();
            filesPanel.showCommit(entry.id());
        });
        logPanel.setOnCreatePatch(entry -> createBinaryPatchForCommit(entry.id(), entry.shortId()));
        logPanel.setOnCreateRangePatch((older, newer) -> {
            if (newer == null) {
                return;
            }
            if (older == null) {
                String from = newer.parentIds() != null && !newer.parentIds().isEmpty()
                        ? newer.parentIds().getFirst().substring(0, 7)
                        : "";
                createBinaryPatchRange(from, newer.shortId());
                return;
            }
            // 2 commits selected: older..newer without re-prompting for refs
            createBinaryPatchRangeDirect(older.id(), newer.id(),
                    "range-" + older.shortId() + "-" + newer.shortId() + ".patch");
        });
        installPatchPopup(stagedList, true);
        installPatchPopup(changesList, false);
        filesPanel.setOnShowDiff(texts -> onShowDiff.accept(texts, false));
        filesPanel.setOnStatus(msg -> statusLabel.setText(msg));
        stashPanel.setOnStatus(msg -> statusLabel.setText(msg));
        stashPanel.setOnStashSelected(entry -> {
            if (entry == null) {
                return;
            }
            logPanel.clearSelection();
            filesPanel.showCommit(entry.id());
            statusLabel.setText("Stash: " + entry.ref());
        });
        stashPanel.setOnChanged(() -> {
            refreshChangesOnly();
            onRepositoryReset.run();
        });

        installDiffOnClick(stagedList, true);
        installDiffOnClick(changesList, false);
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

        JPanel commitBar = new JPanel(new BorderLayout(4, 4));
        commitBar.add(commitMessage, BorderLayout.CENTER);
        commitBar.add(commit, BorderLayout.EAST);
        commitMessage.putClientProperty("JTextField.placeholderText", "Message (Ctrl+Enter to commit)");

        // Left: branch → commit → STAGED/CHANGES/STASH
        JPanel leftNorth = new JPanel(new BorderLayout(0, 8));
        leftNorth.add(header, BorderLayout.NORTH);
        leftNorth.add(commitBar, BorderLayout.SOUTH);

        JPanel leftStack = new JPanel(new BorderLayout(0, 8));
        leftStack.add(leftNorth, BorderLayout.NORTH);
        leftStack.add(changesAndStash, BorderLayout.CENTER);

        // HISTORY | FILES as independent full-height columns (equal share of remaining 2/3)
        historyFilesSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, logPanel, filesPanel);
        historyFilesSplit.setResizeWeight(0.5);
        historyFilesSplit.setContinuousLayout(true);

        // CHANGES | (HISTORY+FILES) → overall CHANGES:HISTORY:FILES = 1:1:1
        bodySplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftStack, historyFilesSplit);
        bodySplit.setResizeWeight(1.0 / 3.0);
        bodySplit.setContinuousLayout(true);

        add(bodySplit, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Balance CHANGES | HISTORY | FILES to 1:1:1
     * (with the editor at 2× via MainFrame main split → overall 1:1:1:2).
     */
    public void balanceColumns() {
        if (bodySplit == null || historyFilesSplit == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (bodySplit.getWidth() <= 0) {
                SwingUtilities.invokeLater(this::balanceColumns);
                return;
            }
            bodySplit.setDividerLocation(1.0 / 3.0);
            historyFilesSplit.setDividerLocation(0.5);
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
                    branchLabel.setText(data.branch());
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

    /**
     * Selection changes show the diff. A click on the already selected row does not
     * fire a selection event, so that click shows the diff as well.
     * Both paths can run for one click; the later request wins.
     */
    private void installDiffOnClick(JList<GitChange> list, boolean staged) {
        int[] request = { 0 };
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                scheduleDiff(request, leadChange(list), staged);
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1) {
                    return;
                }
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                Rectangle cell = list.getCellBounds(index, index);
                if (cell == null || !cell.contains(e.getPoint())) {
                    return;
                }
                scheduleDiff(request, list.getModel().getElementAt(index), staged);
            }
        });
    }

    private void scheduleDiff(int[] request, GitChange change, boolean staged) {
        int mine = ++request[0];
        SwingUtilities.invokeLater(() -> {
            if (mine == request[0]) {
                showDiff(change, staged);
            }
        });
    }

    private static GitChange leadChange(JList<GitChange> list) {
        int lead = list.getLeadSelectionIndex();
        if (lead >= 0 && lead < list.getModel().getSize()) {
            return list.getModel().getElementAt(lead);
        }
        return list.getSelectedValue();
    }

    private void installOpenOnDoubleClick(JList<GitChange> list) {
        list.setToolTipText("クリックで差分を表示、ダブルクリックでファイルを開く");
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

    private void installPatchPopup(JList<GitChange> list, boolean staged) {
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger() || git == null) {
                    return;
                }
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && !list.isSelectedIndex(index)) {
                    list.setSelectedIndex(index);
                }
                JPopupMenu menu = new JPopupMenu();
                JMenuItem selected = new JMenuItem("選択ファイルのパッチを作成…");
                JMenuItem all = new JMenuItem(staged
                        ? "ステージ済み全体のパッチを作成…"
                        : "作業ツリー全体のパッチを作成…");
                selected.addActionListener(ev -> {
                    List<String> paths = selectedPaths(list);
                    if (paths.isEmpty()) {
                        statusLabel.setText("ファイルを選択してください");
                        return;
                    }
                    createBinaryPatch(staged, paths, defaultPatchName(staged ? "staged" : "worktree"));
                });
                all.addActionListener(ev -> {
                    if (staged) {
                        createBinaryPatchStaged();
                    } else {
                        createBinaryPatchWorktree();
                    }
                });
                menu.add(selected);
                menu.add(all);
                menu.show(list, e.getX(), e.getY());
            }
        });
    }

    /** {@code git diff --binary} for the working tree. */
    public void createBinaryPatchWorktree() {
        createBinaryPatch(false, List.of(), defaultPatchName("worktree"));
    }

    /** {@code git diff --binary --cached} for the index. */
    public void createBinaryPatchStaged() {
        createBinaryPatch(true, List.of(), defaultPatchName("staged"));
    }

    public void createBinaryPatchForCommit(String commitId, String shortId) {
        if (git == null) {
            statusLabel.setText("リポジトリがありません");
            return;
        }
        String suggest = "commit-" + (shortId == null || shortId.isBlank()
                ? "patch"
                : shortId) + ".patch";
        Path out = choosePatchFile(suggest);
        if (out == null) {
            return;
        }
        statusLabel.setText("パッチ作成中…");
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                byte[] bytes = git.createBinaryPatchForCommit(commitId);
                if (bytes.length == 0) {
                    throw new IllegalStateException("差分が空です");
                }
                Files.write(out, bytes);
                return out;
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    statusLabel.setText("パッチ作成: " + saved.getFileName());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText("パッチ作成失敗");
                    JOptionPane.showMessageDialog(GitPanel.this, cause.getMessage(),
                            "パッチ作成", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Dialog for {@code git diff --binary <from>..<to>}. */
    public void createBinaryPatchRange() {
        createBinaryPatchRange("", "HEAD");
    }

    /** Save {@code git diff --binary <from>..<to>} after choosing a file (no ref dialog). */
    public void createBinaryPatchRangeDirect(String fromRef, String toRef, String suggestName) {
        if (git == null) {
            statusLabel.setText("リポジトリがありません");
            return;
        }
        String from = fromRef == null ? "" : fromRef.trim();
        String to = toRef == null ? "" : toRef.trim();
        if (from.isEmpty() || to.isEmpty()) {
            JOptionPane.showMessageDialog(this, "開始と終了を両方指定してください。",
                    "パッチ作成", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path out = choosePatchFile(suggestName != null ? suggestName : defaultPatchName("range"));
        if (out == null) {
            return;
        }
        statusLabel.setText("パッチ作成中…");
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                byte[] bytes = git.createBinaryPatchRange(from, to);
                if (bytes.length == 0) {
                    throw new IllegalStateException("差分が空です");
                }
                Files.write(out, bytes);
                return out;
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    statusLabel.setText("パッチ作成: " + saved.getFileName()
                            + " (" + from + ".." + to + ")");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText("パッチ作成失敗");
                    JOptionPane.showMessageDialog(GitPanel.this, cause.getMessage(),
                            "パッチ作成", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public void createBinaryPatchRange(String defaultFrom, String defaultTo) {
        if (git == null) {
            statusLabel.setText("リポジトリがありません");
            return;
        }
        JTextField fromField = new JTextField(defaultFrom == null ? "" : defaultFrom, 28);
        JTextField toField = new JTextField(defaultTo == null ? "" : defaultTo, 28);
        fromField.putClientProperty("JTextField.placeholderText", "例: abc1234, main, HEAD~5");
        toField.putClientProperty("JTextField.placeholderText", "例: HEAD, def5678");

        JPanel form = new JPanel(new BorderLayout(8, 8));
        JPanel labels = new JPanel(new BorderLayout(0, 8));
        JLabel fromLabel = new JLabel("開始 (from)");
        JLabel toLabel = new JLabel("終了 (to)");
        fromLabel.setHorizontalAlignment(SwingConstants.LEFT);
        toLabel.setHorizontalAlignment(SwingConstants.LEFT);
        labels.add(fromLabel, BorderLayout.NORTH);
        labels.add(toLabel, BorderLayout.SOUTH);
        JPanel fields = new JPanel(new BorderLayout(0, 8));
        fields.add(fromField, BorderLayout.NORTH);
        fields.add(toField, BorderLayout.SOUTH);
        form.add(labels, BorderLayout.WEST);
        form.add(fields, BorderLayout.CENTER);
        form.add(new JLabel("git diff --binary <開始>..<終了>"), BorderLayout.SOUTH);

        int ok = JOptionPane.showConfirmDialog(
                this,
                form,
                "コミット範囲のパッチ作成",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        String from = fromField.getText().trim();
        String to = toField.getText().trim();
        if (from.isEmpty() || to.isEmpty()) {
            JOptionPane.showMessageDialog(this, "開始と終了を両方指定してください。",
                    "パッチ作成", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String suggest;
        try {
            String a = git.shortIdOf(from);
            String b = git.shortIdOf(to);
            suggest = "range-" + (a != null ? a : "from") + "-" + (b != null ? b : "to") + ".patch";
        } catch (Exception ignored) {
            suggest = defaultPatchName("range");
        }
        Path out = choosePatchFile(suggest);
        if (out == null) {
            return;
        }
        statusLabel.setText("パッチ作成中…");
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                byte[] bytes = git.createBinaryPatchRange(from, to);
                if (bytes.length == 0) {
                    throw new IllegalStateException("差分が空です");
                }
                Files.write(out, bytes);
                return out;
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    statusLabel.setText("パッチ作成: " + saved.getFileName()
                            + " (" + from + ".." + to + ")");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText("パッチ作成失敗");
                    JOptionPane.showMessageDialog(GitPanel.this, cause.getMessage(),
                            "パッチ作成", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void createBinaryPatch(boolean staged, List<String> paths, String suggestName) {
        if (git == null) {
            statusLabel.setText("リポジトリがありません");
            return;
        }
        Path out = choosePatchFile(suggestName);
        if (out == null) {
            return;
        }
        statusLabel.setText("パッチ作成中…");
        List<String> pathCopy = List.copyOf(paths);
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                byte[] bytes = staged
                        ? git.createBinaryPatchStaged(pathCopy)
                        : git.createBinaryPatchWorktree(pathCopy);
                if (bytes.length == 0) {
                    throw new IllegalStateException("差分が空です");
                }
                Files.write(out, bytes);
                return out;
            }

            @Override
            protected void done() {
                try {
                    Path saved = get();
                    statusLabel.setText("パッチ作成: " + saved.getFileName());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText("パッチ作成失敗");
                    JOptionPane.showMessageDialog(GitPanel.this, cause.getMessage(),
                            "パッチ作成", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private Path choosePatchFile(String suggestName) {
        JFileChooser chooser = new JFileChooser();
        if (git != null) {
            chooser.setCurrentDirectory(git.workspaceRoot().toFile());
        }
        chooser.setSelectedFile(new java.io.File(suggestName));
        chooser.setDialogTitle("パッチファイルの保存");
        chooser.setFileFilter(new FileNameExtensionFilter("Patch (*.patch, *.diff)", "patch", "diff"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path out = chooser.getSelectedFile().toPath();
        String name = out.getFileName().toString();
        if (!name.contains(".")) {
            out = out.resolveSibling(name + ".patch");
        }
        if (Files.exists(out)) {
            int overwrite = JOptionPane.showConfirmDialog(
                    this,
                    out.getFileName() + " は既に存在します。上書きしますか？",
                    "パッチ作成",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (overwrite != JOptionPane.YES_OPTION) {
                return null;
            }
        }
        return out;
    }

    private static String defaultPatchName(String kind) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return kind + "-" + stamp + ".patch";
    }

    private static List<String> selectedPaths(JList<GitChange> list) {
        List<String> paths = new ArrayList<>();
        for (GitChange change : list.getSelectedValuesList()) {
            if (change != null) {
                paths.add(change.path());
            }
        }
        return paths;
    }

    private void showDiff(GitChange change, boolean staged) {
        if (git == null || change == null) {
            return;
        }
        GitService service = git;
        String path = change.path();
        boolean wantStaged = staged;
        statusLabel.setText("Diff 読込中… " + path);
        new SwingWorker<DiffTexts, Void>() {
            @Override
            protected DiffTexts doInBackground() throws Exception {
                return service.loadDiffTexts(path, wantStaged);
            }

            @Override
            protected void done() {
                if (git != service) {
                    return;
                }
                try {
                    DiffTexts texts = get();
                    onShowDiff.accept(texts, wantStaged);
                    statusLabel.setText((wantStaged ? "Staged" : "Changes") + ": " + path);
                } catch (Exception ex) {
                    statusLabel.setText("Diff error: " + ex.getMessage());
                }
            }
        }.execute();
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
                    String prefix = change.statusLabel() + "  ";
                    int max = Math.max(24, list.getWidth() / 8 - prefix.length());
                    String shown = PathEllipsis.middle(change.path(), max);
                    setText(prefix + shown);
                    setToolTipText(change.path());
                }
                return this;
            }
        });
    }

    private record RefreshData(String branch, List<GitChange> changes) {
    }
}
