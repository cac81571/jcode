package com.jcode.ui;

import com.jcode.service.ExternalOpener;
import com.jcode.service.GitService;
import com.jcode.service.RecentItems;
import com.jcode.service.SakuraLauncher;
import com.jcode.ui.editor.CodeEditorPane;
import com.jcode.ui.editor.EditorTabManager;
import com.jcode.ui.editor.JavaOutlinePanel;
import com.jcode.ui.explorer.FileHistoryPanel;
import com.jcode.ui.explorer.FileTreePanel;
import com.jcode.ui.explorer.QuickOpenDialog;
import com.jcode.ui.git.GitPanel;
import com.jcode.ui.search.FindReplaceBar;
import com.jcode.ui.search.SearchSidebar;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MainFrame extends JFrame {

    private final FileTreePanel fileTree = new FileTreePanel();
    private final EditorTabManager editors = new EditorTabManager();
    private final SearchSidebar searchSidebar = new SearchSidebar(this::getWorkspaceRoot);
    private final GitPanel gitPanel = new GitPanel();
    private final FileHistoryPanel fileHistoryPanel = new FileHistoryPanel();
    private final JavaOutlinePanel javaOutlinePanel = new JavaOutlinePanel();
    private final FindReplaceBar findReplaceBar = new FindReplaceBar(editors::getActiveEditor);
    private final StatusBar statusBar = new StatusBar();
    private final JTabbedPane activityBar = new JTabbedPane(JTabbedPane.TOP);
    private final Timer outlineTimer = new Timer(300, e -> refreshOutline());
    private JSplitPane editorSplit;
    private JSplitPane sideSplit;
    private boolean fileHistoryVisible;

    /** Default activity-bar width; Git uses 3/5 of the window (1:1:1:2 with editor). */
    private static final int SIDEBAR_WIDTH = 420;
    /** Git columns + editor = 1+1+1+2 → left pane is 3/5 of the frame. */
    private static final double GIT_SIDEBAR_RATIO = 0.6;
    /** Explorer : editor : outline = 1 : 2 : 1 → left pane is 1/4 of the frame. */
    private static final double EXPLORER_SIDEBAR_RATIO = 0.25;

    private Path workspaceRoot;
    private GitService gitService;
    private JMenu recentMenu;
    private JSplitPane mainSplit;
    private int sidebarWidthBeforeGit = SIDEBAR_WIDTH;
    private boolean gitSidebarActive;

    public MainFrame() {
        super("jcode");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));
        setPreferredSize(new Dimension(1400, 900));
        setLocationByPlatform(true);

        buildMenu();
        buildLayout();
        bindActions();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        Timer dirtyTimer = new Timer(500, e -> editors.refreshDirtyIndicators());
        dirtyTimer.start();

        pack();
    }

    /** Restore last folder when launched with no path argument. */
    public void restoreSessionIfNeeded() {
        if (workspaceRoot != null) {
            return;
        }
        Path last = RecentItems.lastFolder();
        if (last != null) {
            openWorkspace(last);
        }
    }

    public void openWorkspace(Path path) {
        if (path == null) {
            return;
        }
        Path root = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            if (Files.isRegularFile(root)) {
                openWorkspace(root.getParent());
                openFile(root);
            }
            return;
        }
        this.workspaceRoot = root;
        fileTree.setRoot(root);
        setTitle("jcode — " + root.getFileName());
        statusBar.setLeft(root.toString());
        RecentItems.rememberFolder(root);
        refreshRecentMenu();
        reconnectGit();
    }

    private Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    private void buildLayout() {
        activityBar.addTab("エクスプローラー", fileTree);
        activityBar.addTab("検索", searchSidebar);
        activityBar.addTab("Git", gitPanel);
        activityBar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 600));
        activityBar.setMinimumSize(new Dimension(280, 200));

        JPanel editorArea = new JPanel(new BorderLayout());
        editorArea.add(findReplaceBar, BorderLayout.NORTH);
        editorArea.add(editors.getComponent(), BorderLayout.CENTER);

        fileHistoryPanel.setOnShowDiff(texts -> editors.showGitDiff(texts, false));
        fileHistoryPanel.setOnClose(this::hideFileHistoryPanel);
        javaOutlinePanel.setOnJump(line -> {
            CodeEditorPane editor = editors.getActiveEditor();
            if (editor != null && !editor.isVirtual()) {
                editor.goTo(line, 1);
            }
        });
        outlineTimer.setRepeats(false);

        sideSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, javaOutlinePanel, null);
        sideSplit.setResizeWeight(1.0);
        sideSplit.setDividerSize(0);
        sideSplit.setContinuousLayout(true);

        editorSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorArea, null);
        editorSplit.setResizeWeight(1.0);
        editorSplit.setDividerSize(0);
        editorSplit.setContinuousLayout(true);
        javax.swing.SwingUtilities.invokeLater(this::applyRightPane);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, activityBar, editorSplit);
        mainSplit.setResizeWeight(0.28);
        mainSplit.setDividerLocation(SIDEBAR_WIDTH);

        activityBar.addChangeListener(e -> {
            syncSidebarWidthForActivityTab();
            applyRightPane();
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        root.add(mainSplit, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);
    }

    /** Widen the left pane only while Git is open; restore prior width otherwise. */
    private void syncSidebarWidthForActivityTab() {
        if (mainSplit == null) {
            return;
        }
        boolean gitOpen = activityBar.getSelectedComponent() == gitPanel;
        if (gitOpen && !gitSidebarActive) {
            sidebarWidthBeforeGit = mainSplit.getDividerLocation();
            gitSidebarActive = true;
            applyGitColumnRatios();
        } else if (gitOpen) {
            applyGitColumnRatios();
        } else if (gitSidebarActive) {
            gitSidebarActive = false;
            if (explorerOpen()) {
                applyExplorerColumnRatios();
            } else {
                mainSplit.setDividerLocation(sidebarWidthBeforeGit);
            }
        } else if (explorerOpen()) {
            applyExplorerColumnRatios();
        }
    }

    /** Explorer : editor : outline = 1 : 2 : 1. */
    private void applyExplorerColumnRatios() {
        if (mainSplit == null || !explorerOpen()) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (!explorerOpen()) {
                return;
            }
            int total = mainSplit.getWidth();
            if (total <= 0) {
                javax.swing.SwingUtilities.invokeLater(this::applyExplorerColumnRatios);
                return;
            }
            mainSplit.setResizeWeight(EXPLORER_SIDEBAR_RATIO);
            mainSplit.setDividerLocation((int) Math.round(total * EXPLORER_SIDEBAR_RATIO));
            javax.swing.SwingUtilities.invokeLater(this::balanceOutlineSplit);
        });
    }

    /** CHANGES : HISTORY : FILES : editor = 1 : 1 : 1 : 3 */
    private void applyGitColumnRatios() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            int total = mainSplit.getWidth();
            if (total <= 0) {
                javax.swing.SwingUtilities.invokeLater(this::applyGitColumnRatios);
                return;
            }
            mainSplit.setDividerLocation((int) Math.round(total * GIT_SIDEBAR_RATIO));
            gitPanel.balanceColumns();
        });
    }

    private void buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("ファイル");
        file.add(item("フォルダを開く…", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> openFolder()));
        file.add(item("ファイルを開く…", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), e -> openFileDialog()));
        file.add(item("ファイルへ移動…", KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), e -> showQuickOpen()));
        recentMenu = new JMenu("最近使った項目");
        file.add(recentMenu);
        refreshRecentMenu();
        file.addSeparator();
        file.add(item("外部エディタで開く", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), e -> openInExternalEditor()));
        file.add(item("サクラエディタで開く", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.ALT_DOWN_MASK), e -> openInSakura()));
        file.add(item("サクラエディタのパスを設定…", null, e -> configureSakuraPath()));
        file.addSeparator();
        file.add(item("保存", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), e -> save()));
        file.add(item("すべて保存", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> saveAll()));
        file.add(item("エディタを閉じる", KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), e -> editors.closeActive()));
        file.addSeparator();
        file.add(item("終了", KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK), e -> exit()));

        JMenu edit = new JMenu("編集");
        edit.add(item("検索", KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), e -> findReplaceBar.showFind()));
        edit.add(item("置換", KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), e -> findReplaceBar.showReplace()));
        edit.add(item("ファイル横断検索", KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> {
            activityBar.setSelectedComponent(searchSidebar);
            searchSidebar.focusSearch();
        }));

        JMenu view = new JMenu("表示");
        view.add(item("エクスプローラー", KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> activityBar.setSelectedComponent(fileTree)));
        view.add(item("検索", null, e -> {
            activityBar.setSelectedComponent(searchSidebar);
            searchSidebar.focusSearch();
        }));
        view.add(item("ソース管理", KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> {
            activityBar.setSelectedComponent(gitPanel);
            gitPanel.refresh();
        }));
        view.add(item("エクスプローラーを更新", KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), e -> fileTree.refresh()));
        view.addSeparator();

        JMenu theme = new JMenu("テーマ");
        JRadioButtonMenuItem darkItem = new JRadioButtonMenuItem("ダーク", ThemeManager.isDark());
        JRadioButtonMenuItem lightItem = new JRadioButtonMenuItem("ライト", !ThemeManager.isDark());
        ButtonGroup themeGroup = new ButtonGroup();
        themeGroup.add(darkItem);
        themeGroup.add(lightItem);
        darkItem.addActionListener(e -> ThemeManager.setMode(ThemeManager.Mode.DARK));
        lightItem.addActionListener(e -> ThemeManager.setMode(ThemeManager.Mode.LIGHT));
        theme.add(darkItem);
        theme.add(lightItem);
        theme.addSeparator();
        theme.add(item("ダーク / ライト切替", KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), e -> {
            ThemeManager.toggle();
            darkItem.setSelected(ThemeManager.isDark());
            lightItem.setSelected(!ThemeManager.isDark());
        }));
        view.add(theme);

        ThemeManager.addListener(() -> {
            darkItem.setSelected(ThemeManager.isDark());
            lightItem.setSelected(!ThemeManager.isDark());
            editors.applyThemeToAll();
            statusBar.setLeft(ThemeManager.isDark() ? "テーマ: ダーク" : "テーマ: ライト");
        });

        JMenu git = new JMenu("Git");
        git.add(item("更新", null, e -> gitPanel.refresh()));
        git.add(item("リポジトリを初期化", null, e -> initGit()));
        git.addSeparator();
        git.add(item("作業ツリーのパッチを作成…", null, e -> gitPanel.createBinaryPatchWorktree()));
        git.add(item("ステージ済みのパッチを作成…", null, e -> gitPanel.createBinaryPatchStaged()));
        git.add(item("コミット範囲のパッチを作成…", null, e -> gitPanel.createBinaryPatchRange()));

        bar.add(file);
        bar.add(edit);
        bar.add(view);
        bar.add(git);
        setJMenuBar(bar);
    }

    private void bindActions() {
        // Ensure Ctrl+P works even when the editor text area has focus
        JPanel root = (JPanel) getContentPane();
        root.getInputMap(JPanel.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), "quickOpen");
        root.getActionMap().put("quickOpen", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showQuickOpen();
            }
        });

        fileTree.setOnOpenFile(this::openFileInSameTab);
        fileTree.setOnOpenInNewTab(this::openFile);
        fileTree.setOnShowFileHistory(this::showFileHistory);
        searchSidebar.setActiveFileSupplier(() -> {
            CodeEditorPane editor = editors.getActiveEditor();
            if (editor == null || editor.isVirtual()) {
                return null;
            }
            return new SearchSidebar.ActiveFile(editor.getFile(), editor.getText());
        });
        searchSidebar.setOnOpenMatch((path, match) -> {
            openFile(path);
            CodeEditorPane editor = editors.getActiveEditor();
            if (editor != null) {
                editor.highlightAllMatches(
                        searchSidebar.lastQuery(),
                        searchSidebar.isMatchCase(),
                        searchSidebar.isWholeWord(),
                        searchSidebar.isRegex()
                );
                editor.highlightRange(match.lineNumber(), match.startColumn(), match.endColumn());
            }
        });
        searchSidebar.setOnOpenInEditor(request -> {
            try {
                CodeEditorPane pane = editors.openVirtual(request.title(), request.content());
                pane.highlightSearchReport(
                        request.query(),
                        request.matchCase(),
                        request.wholeWord(),
                        request.regex()
                );
                statusBar.setLeft("検索結果をテキストで表示（ヒット箇所をハイライト）");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "検索結果の表示に失敗しました:\n" + ex.getMessage(),
                        "エディタで開く", JOptionPane.ERROR_MESSAGE);
            }
        });
        searchSidebar.setOnOpenTable(request -> {
            editors.showSearchResultsTable(
                    request.title(), request.matches(), workspaceRoot, request.contextLines());
            statusBar.setLeft("検索結果をテーブルで表示");
        });
        searchSidebar.setOnReplaced(result -> {
            editors.reloadFromDisk(result.changedFiles());
            fileTree.refresh();
            gitPanel.refresh();
            statusBar.setLeft(result.filesChanged() + " ファイル / "
                    + result.replacements() + " 件を置換しました");
        });
        gitPanel.setOnOpenFile(this::openFile);
        gitPanel.setOnShowDiff((texts, staged) -> {
            // Path is already shown on GitPanel's own status line — avoid duplicating on statusBar
            editors.showGitDiff(texts, staged);
        });
        gitPanel.setOnWorkingTreeChanged(path -> {
            editors.reloadFromDisk(java.util.List.of(path));
            fileTree.refresh();
        });
        gitPanel.setOnRepositoryReset(() -> {
            editors.reloadAllFromDisk();
            fileTree.refresh();
        });
        gitPanel.getInitButton().addActionListener(e -> initGit());

        editors.setOnActiveChanged(editor -> {
            if (editor == null) {
                return;
            }
            Path file = editor.getFile();
            String rel = workspaceRoot != null && file.startsWith(workspaceRoot)
                    ? workspaceRoot.relativize(file).toString()
                    : file.toString();
            statusBar.setRight(rel.replace('\\', '/'));
            if (!editor.isVirtual()) {
                fileTree.reveal(file);
                syncFileHistoryWithEditor(file);
            }
            refreshOutline();
        });
        editors.setOnTextChanged(editor -> outlineTimer.restart());
    }

    private void openFolder() {
        JFileChooser chooser = new JFileChooser(workspaceRoot == null ? null : workspaceRoot.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Open Folder");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openWorkspace(chooser.getSelectedFile().toPath());
        }
    }

    private void openFileDialog() {
        JFileChooser chooser = new JFileChooser(workspaceRoot == null ? null : workspaceRoot.toFile());
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    private void showQuickOpen() {
        if (workspaceRoot == null) {
            JOptionPane.showMessageDialog(this, "先にフォルダを開いてください。",
                    "Go to File", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        activityBar.setSelectedComponent(fileTree);
        QuickOpenDialog dialog = new QuickOpenDialog(this, workspaceRoot, this::openFile);
        dialog.setVisible(true);
    }

    private void openFile(Path path) {
        openEditor(path, false);
    }

    private void openFileInSameTab(Path path) {
        openEditor(path, true);
    }

    private void openEditor(Path path, boolean sameTab) {
        try {
            CodeEditorPane opened = sameTab ? editors.openInPreview(path) : editors.open(path);
            if (opened == null) {
                return;
            }
            RecentItems.rememberFile(path);
            refreshRecentMenu();
            statusBar.setLeft("Opened " + path.getFileName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Cannot open file:\n" + ex.getMessage(),
                    "Open", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showFileHistory(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        if (gitService == null || !gitService.isRepository()) {
            JOptionPane.showMessageDialog(this,
                    "Git リポジトリがありません。",
                    "変更履歴",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (gitService.toRepoRelativePath(path) == null) {
            JOptionPane.showMessageDialog(this,
                    "このファイルはリポジトリ外です。",
                    "変更履歴",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        showFileHistoryPanel(path);
    }

    private void showFileHistoryPanel(Path path) {
        fileHistoryPanel.showFile(gitService, path, true);
        fileHistoryVisible = true;
        applyRightPane();
    }

    /** While the history panel is open, follow the active editor file (list only, no auto-diff). */
    private void syncFileHistoryWithEditor(Path path) {
        if (!fileHistoryVisible || path == null || !Files.isRegularFile(path)) {
            return;
        }
        if (gitService == null || !gitService.isRepository()) {
            fileHistoryPanel.showUnavailable(path, "Git リポジトリがありません");
            return;
        }
        if (gitService.toRepoRelativePath(path) == null) {
            fileHistoryPanel.showUnavailable(path, "リポジトリ外のファイル");
            return;
        }
        fileHistoryPanel.showFile(gitService, path, false);
    }

    private boolean explorerOpen() {
        return activityBar.getSelectedComponent() == fileTree;
    }

    /** Outline only while Explorer is selected. History stays if it was opened. */
    private void applyRightPane() {
        if (editorSplit == null) {
            return;
        }
        boolean showOutline = explorerOpen();
        if (!showOutline && !fileHistoryVisible) {
            editorSplit.setRightComponent(null);
            editorSplit.setDividerSize(0);
            editorSplit.setDividerLocation(1.0);
            return;
        }
        editorSplit.setDividerSize(6);
        if (showOutline && fileHistoryVisible) {
            sideSplit.setTopComponent(javaOutlinePanel);
            sideSplit.setBottomComponent(fileHistoryPanel);
            sideSplit.setDividerSize(6);
            editorSplit.setResizeWeight(2.0 / 3.0);
            editorSplit.setRightComponent(sideSplit);
            javax.swing.SwingUtilities.invokeLater(() -> {
                applyExplorerColumnRatios();
                balanceFileHistorySplit();
            });
            return;
        }
        if (showOutline) {
            editorSplit.setResizeWeight(2.0 / 3.0);
            editorSplit.setRightComponent(javaOutlinePanel);
            javax.swing.SwingUtilities.invokeLater(this::applyExplorerColumnRatios);
            return;
        }
        editorSplit.setResizeWeight(1.0);
        editorSplit.setRightComponent(fileHistoryPanel);
        javax.swing.SwingUtilities.invokeLater(this::balanceOutlineSplit);
    }

    private void balanceOutlineSplit() {
        if (editorSplit == null || editorSplit.getRightComponent() == null) {
            return;
        }
        int total = editorSplit.getWidth();
        if (total <= 0) {
            javax.swing.SwingUtilities.invokeLater(this::balanceOutlineSplit);
            return;
        }
        if (explorerOpen()) {
            // Right pane is editor+outline = 3 parts; outline is 1 of those.
            editorSplit.setDividerLocation(2.0 / 3.0);
            return;
        }
        int sideWidth = Math.min(360, Math.max(260, total / 4));
        editorSplit.setDividerLocation(total - sideWidth);
    }

    private void balanceFileHistorySplit() {
        if (!fileHistoryVisible || sideSplit == null) {
            return;
        }
        int total = sideSplit.getHeight();
        if (total <= 0) {
            javax.swing.SwingUtilities.invokeLater(this::balanceFileHistorySplit);
            return;
        }
        sideSplit.setDividerLocation(Math.max(120, total / 2));
    }

    private void hideFileHistoryPanel() {
        fileHistoryVisible = false;
        fileHistoryPanel.clear();
        if (sideSplit != null) {
            sideSplit.setBottomComponent(null);
            sideSplit.setDividerSize(0);
        }
        applyRightPane();
    }

    private void refreshOutline() {
        CodeEditorPane editor = editors.getActiveEditor();
        if (editor == null || editor.isVirtual()) {
            javaOutlinePanel.showMessage("ファイルを開いてください");
            return;
        }
        Path file = editor.getFile();
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".java")) {
            javaOutlinePanel.showMessage("Java ファイルではありません");
            return;
        }
        javaOutlinePanel.showSource(name, editor.getText());
    }

    private void openInExternalEditor() {
        Path path = activeDiskFile();
        if (path == null) {
            JOptionPane.showMessageDialog(this, "開いているファイルがありません。",
                    "外部エディタで開く", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            ExternalOpener.open(path);
            statusBar.setLeft("外部アプリで開きました: " + path.getFileName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "外部アプリで開けませんでした:\n" + ex.getMessage(),
                    "外部エディタで開く", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openInSakura() {
        Path path = activeDiskFile();
        if (path == null) {
            JOptionPane.showMessageDialog(this, "開いているファイルがありません。",
                    "サクラエディタで開く", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            SakuraLauncher.open(path);
            statusBar.setLeft("サクラエディタで開きました: " + path.getFileName());
        } catch (IOException ex) {
            int choice = JOptionPane.showConfirmDialog(this,
                    ex.getMessage() + "\n\nsakura.exe の場所を設定しますか？",
                    "サクラエディタで開く",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                configureSakuraPath();
            }
        }
    }

    private Path activeDiskFile() {
        CodeEditorPane editor = editors.getActiveEditor();
        if (editor == null || editor.isVirtual()) {
            return null;
        }
        Path path = editor.getFile();
        return Files.isRegularFile(path) ? path : null;
    }

    private void configureSakuraPath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("sakura.exe を選択");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        Path current = SakuraLauncher.resolveExecutable();
        if (current != null) {
            chooser.setSelectedFile(current.toFile());
            chooser.setCurrentDirectory(current.getParent().toFile());
        } else {
            Path guess = Path.of("C:\\Program Files (x86)\\sakura");
            if (Files.isDirectory(guess)) {
                chooser.setCurrentDirectory(guess.toFile());
            }
        }
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("sakura.exe", "exe"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(selected)) {
            JOptionPane.showMessageDialog(this, "有効なファイルを選んでください。",
                    "サクラエディタのパス", JOptionPane.WARNING_MESSAGE);
            return;
        }
        SakuraLauncher.setConfiguredPath(selected.toString());
        statusBar.setLeft("サクラエディタ: " + selected);
    }

    private void refreshRecentMenu() {
        if (recentMenu == null) {
            return;
        }
        recentMenu.removeAll();

        List<Path> folders = RecentItems.folders();
        List<Path> files = RecentItems.files();

        if (folders.isEmpty() && files.isEmpty()) {
            JMenuItem empty = new JMenuItem("(履歴なし)");
            empty.setEnabled(false);
            recentMenu.add(empty);
            return;
        }

        if (!folders.isEmpty()) {
            JMenuItem header = new JMenuItem("フォルダ");
            header.setEnabled(false);
            recentMenu.add(header);
            for (Path folder : folders) {
                JMenuItem item = new JMenuItem(recentLabel(folder, true));
                item.setToolTipText(folder.toString());
                item.addActionListener(e -> openRecentFolder(folder));
                recentMenu.add(item);
            }
        }

        if (!files.isEmpty()) {
            if (!folders.isEmpty()) {
                recentMenu.addSeparator();
            }
            JMenuItem header = new JMenuItem("ファイル");
            header.setEnabled(false);
            recentMenu.add(header);
            for (Path file : files) {
                JMenuItem item = new JMenuItem(recentLabel(file, false));
                item.setToolTipText(file.toString());
                item.addActionListener(e -> openRecentFile(file));
                recentMenu.add(item);
            }
        }

        recentMenu.addSeparator();
        recentMenu.add(item("履歴をクリア", null, e -> {
            RecentItems.clear();
            refreshRecentMenu();
            statusBar.setLeft("最近使った項目をクリアしました");
        }));
    }

    private void openRecentFolder(Path folder) {
        if (!Files.isDirectory(folder)) {
            JOptionPane.showMessageDialog(this, "フォルダが見つかりません:\n" + folder,
                    "最近使った項目", JOptionPane.WARNING_MESSAGE);
            refreshRecentMenu();
            return;
        }
        openWorkspace(folder);
    }

    private void openRecentFile(Path file) {
        if (!Files.isRegularFile(file)) {
            JOptionPane.showMessageDialog(this, "ファイルが見つかりません:\n" + file,
                    "最近使った項目", JOptionPane.WARNING_MESSAGE);
            refreshRecentMenu();
            return;
        }
        Path parent = file.getParent();
        if (workspaceRoot == null && parent != null) {
            openWorkspace(parent);
        }
        openFile(file);
    }

    private static String recentLabel(Path path, boolean folder) {
        String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        Path parent = path.getParent();
        String parentText = parent == null ? "" : parent.toString();
        if (parentText.length() > 48) {
            parentText = "…" + parentText.substring(parentText.length() - 47);
        }
        return folder ? name + "  —  " + parentText : name + "  —  " + parentText;
    }

    private void save() {
        try {
            if (editors.saveActive()) {
                statusBar.setLeft("Saved");
                gitPanel.refresh();
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveAll() {
        try {
            editors.saveAll();
            statusBar.setLeft("Saved all");
            gitPanel.refresh();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Save All", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void reconnectGit() {
        hideFileHistoryPanel();
        gitPanel.clear();
        if (workspaceRoot == null) {
            return;
        }
        try {
            gitService = GitService.open(workspaceRoot);
            if (gitService != null) {
                gitPanel.bind(gitService);
                statusBar.setLeft("Git: " + gitService.currentBranch());
            } else {
                statusBar.setLeft("Folder opened (not a git repo)");
            }
        } catch (Exception ex) {
            statusBar.setLeft("Git open failed: " + ex.getMessage());
        }
    }

    private void initGit() {
        if (workspaceRoot == null) {
            JOptionPane.showMessageDialog(this, "Open a folder first.");
            return;
        }
        if (gitService != null && gitService.isRepository()) {
            JOptionPane.showMessageDialog(this, "すでに Git 管理されています。",
                    "Git Init", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            gitService = GitService.init(workspaceRoot);
            gitPanel.bind(gitService);
            statusBar.setLeft("Initialized git repository");
            activityBar.setSelectedComponent(gitPanel);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Git Init", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exit() {
        try {
            editors.saveAll();
        } catch (IOException ignored) {
            // still exit
        }
        gitPanel.clear();
        gitService = null;
        dispose();
        System.exit(0);
    }

    private static JMenuItem item(String text, KeyStroke stroke, java.util.function.Consumer<ActionEvent> handler) {
        JMenuItem item = new JMenuItem(new AbstractAction(text) {
            @Override
            public void actionPerformed(ActionEvent e) {
                handler.accept(e);
            }
        });
        if (stroke != null) {
            item.setAccelerator(stroke);
        }
        return item;
    }
}
