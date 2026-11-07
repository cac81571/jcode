package com.jcode.ui.editor;

import com.jcode.model.DiffTexts;
import com.jcode.service.ExternalOpener;
import com.jcode.service.SakuraLauncher;
import com.jcode.ui.git.DiffViewerPanel;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class EditorTabManager {

    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<Path, CodeEditorPane> openEditors = new HashMap<>();
    private DiffViewerPanel diffViewer;
    private Consumer<CodeEditorPane> onActiveChanged = e -> {
    };

    public EditorTabManager() {
        tabs.addChangeListener(e -> {
            CodeEditorPane active = getActiveEditor();
            if (active != null) {
                onActiveChanged.accept(active);
            }
        });
        installTabContextMenu();
    }

    public JTabbedPane getComponent() {
        return tabs;
    }

    public void setOnActiveChanged(Consumer<CodeEditorPane> onActiveChanged) {
        this.onActiveChanged = onActiveChanged;
    }

    public CodeEditorPane open(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        CodeEditorPane existing = openEditors.get(key);
        if (existing != null) {
            tabs.setSelectedComponent(existing.getComponent());
            return existing;
        }

        CodeEditorPane editor = new CodeEditorPane(key);
        openEditors.put(key, editor);
        tabs.addTab(tabTitle(editor), editor.getComponent());
        tabs.setSelectedComponent(editor.getComponent());
        return editor;
    }

    /**
     * Shows a Git diff in the wide editor area (reuses one Diff tab).
     */
    public void showGitDiff(DiffTexts texts, boolean staged) {
        if (texts == null) {
            return;
        }
        if (diffViewer == null) {
            diffViewer = new DiffViewerPanel();
            tabs.addTab(diffTabTitle(texts, staged), diffViewer);
        } else {
            int index = tabs.indexOfComponent(diffViewer);
            if (index < 0) {
                tabs.addTab(diffTabTitle(texts, staged), diffViewer);
            } else {
                tabs.setTitleAt(index, diffTabTitle(texts, staged));
            }
        }
        diffViewer.showDiff(texts);
        tabs.setSelectedComponent(diffViewer);
    }

    /**
     * Opens (or refreshes) a virtual text tab (copyable), e.g. Search Results.
     * Uses a fixed key so Windows path-illegal characters in {@code title} are fine.
     */
    public CodeEditorPane openVirtual(String title, String content) {
        Path key = Path.of(System.getProperty("java.io.tmpdir"), "jcode-virtual", "search-results.txt");
        CodeEditorPane existing = openEditors.get(key);
        if (existing != null) {
            existing.replaceContent(content);
            int index = tabs.indexOfComponent(existing.getComponent());
            if (index >= 0) {
                tabs.setTitleAt(index, title);
            }
            tabs.setSelectedComponent(existing.getComponent());
            existing.getTextArea().requestFocusInWindow();
            return existing;
        }
        CodeEditorPane editor = CodeEditorPane.virtual(title, key, content, SyntaxConstants.SYNTAX_STYLE_NONE);
        openEditors.put(key, editor);
        tabs.addTab(title, editor.getComponent());
        tabs.setSelectedComponent(editor.getComponent());
        editor.getTextArea().requestFocusInWindow();
        return editor;
    }

    public CodeEditorPane getActiveEditor() {
        var selected = tabs.getSelectedComponent();
        if (selected == null) {
            return null;
        }
        return findByComponent(selected);
    }

    public boolean saveActive() throws IOException {
        CodeEditorPane editor = getActiveEditor();
        if (editor == null) {
            return false;
        }
        editor.save();
        refreshTabTitle(editor);
        return true;
    }

    public void saveAll() throws IOException {
        for (CodeEditorPane editor : openEditors.values()) {
            if (editor.isDirty()) {
                editor.save();
                refreshTabTitle(editor);
            }
        }
    }

    public boolean closeActive() {
        if (tabs.getSelectedComponent() == diffViewer) {
            closeDiffTab();
            return true;
        }
        CodeEditorPane editor = getActiveEditor();
        return editor == null || closeEditor(editor);
    }

    public void refreshDirtyIndicators() {
        openEditors.values().forEach(this::refreshTabTitle);
    }

    public void applyThemeToAll() {
        openEditors.values().forEach(CodeEditorPane::applyEditorTheme);
    }

    /** Reload open editors whose files were changed on disk (e.g. after replace-all). */
    public void reloadFromDisk(Iterable<Path> files) {
        for (Path file : files) {
            Path key = file.toAbsolutePath().normalize();
            CodeEditorPane editor = openEditors.get(key);
            if (editor == null || editor.isVirtual()) {
                continue;
            }
            try {
                editor.reloadFromDisk();
                refreshTabTitle(editor);
            } catch (IOException ignored) {
                // leave buffer as-is
            }
        }
    }

    private void installTabContextMenu() {
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int index = tabs.indexAtLocation(e.getX(), e.getY());
                if (index < 0) {
                    return;
                }
                tabs.setSelectedIndex(index);
                showTabMenu(e.getComponent(), e.getX(), e.getY(), index);
            }
        });
    }

    private void showTabMenu(Component invoker, int x, int y, int index) {
        Component component = tabs.getComponentAt(index);
        if (component == diffViewer) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem close = new JMenuItem("閉じる");
            close.addActionListener(e -> closeDiffTab());
            menu.add(close);
            menu.show(invoker, x, y);
            return;
        }

        CodeEditorPane editor = editorAt(index);
        if (editor == null) {
            return;
        }

        JPopupMenu menu = new JPopupMenu();
        JMenuItem close = new JMenuItem("閉じる");
        JMenuItem closeOthers = new JMenuItem("他を閉じる");
        JMenuItem closeAll = new JMenuItem("すべて閉じる");
        JMenuItem closeRight = new JMenuItem("右側を閉じる");
        JMenuItem openExternal = new JMenuItem("外部エディタで開く");
        JMenuItem openSakura = new JMenuItem("サクラエディタで開く");

        close.addActionListener(e -> closeEditor(editor));
        closeOthers.addActionListener(e -> closeOthers(editor));
        closeAll.addActionListener(e -> closeAll());
        closeRight.addActionListener(e -> closeToTheRight(index));
        openExternal.addActionListener(e -> openExternally(editor));
        openSakura.addActionListener(e -> openInSakura(editor));
        boolean diskFile = !editor.isVirtual() && Files.isRegularFile(editor.getFile());
        openExternal.setEnabled(diskFile);
        openSakura.setEnabled(diskFile);

        closeOthers.setEnabled(tabs.getTabCount() > 1);
        closeAll.setEnabled(tabs.getTabCount() > 0);
        closeRight.setEnabled(index < tabs.getTabCount() - 1);

        menu.add(close);
        menu.addSeparator();
        menu.add(closeOthers);
        menu.add(closeRight);
        menu.add(closeAll);
        menu.addSeparator();
        menu.add(openExternal);
        menu.add(openSakura);
        menu.show(invoker, x, y);
    }

    private void openExternally(CodeEditorPane editor) {
        if (editor == null || editor.isVirtual()) {
            return;
        }
        try {
            ExternalOpener.open(editor.getFile());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(tabs,
                    "外部アプリで開けませんでした:\n" + ex.getMessage(),
                    "外部エディタで開く", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openInSakura(CodeEditorPane editor) {
        if (editor == null || editor.isVirtual()) {
            return;
        }
        try {
            SakuraLauncher.open(editor.getFile());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(tabs,
                    ex.getMessage(),
                    "サクラエディタで開く", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void closeDiffTab() {
        if (diffViewer == null) {
            return;
        }
        tabs.remove(diffViewer);
        diffViewer.clear();
    }

    private boolean closeEditor(CodeEditorPane editor) {
        if (!editor.isVirtual() && editor.isDirty()) {
            int choice = JOptionPane.showConfirmDialog(
                    tabs,
                    editor.displayName() + " への変更を保存しますか？",
                    "未保存の変更",
                    JOptionPane.YES_NO_CANCEL_OPTION
            );
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                return false;
            }
            if (choice == JOptionPane.YES_OPTION) {
                try {
                    editor.save();
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(tabs, "保存に失敗しました: " + ex.getMessage());
                    return false;
                }
            }
        }
        tabs.remove(editor.getComponent());
        openEditors.remove(editor.getFile());
        return true;
    }

    private void closeOthers(CodeEditorPane keep) {
        List<CodeEditorPane> toClose = new ArrayList<>();
        for (CodeEditorPane editor : openEditors.values()) {
            if (editor != keep) {
                toClose.add(editor);
            }
        }
        for (CodeEditorPane editor : toClose) {
            if (!closeEditor(editor)) {
                break;
            }
        }
    }

    private void closeAll() {
        List<CodeEditorPane> snapshot = new ArrayList<>(openEditors.values());
        for (CodeEditorPane editor : snapshot) {
            if (!closeEditor(editor)) {
                break;
            }
        }
        closeDiffTab();
    }

    private void closeToTheRight(int fromIndex) {
        List<CodeEditorPane> toClose = new ArrayList<>();
        for (int i = fromIndex + 1; i < tabs.getTabCount(); i++) {
            CodeEditorPane editor = editorAt(i);
            if (editor != null) {
                toClose.add(editor);
            }
        }
        for (CodeEditorPane editor : toClose) {
            if (!closeEditor(editor)) {
                break;
            }
        }
    }

    private CodeEditorPane editorAt(int index) {
        if (index < 0 || index >= tabs.getTabCount()) {
            return null;
        }
        return findByComponent(tabs.getComponentAt(index));
    }

    private CodeEditorPane findByComponent(Component component) {
        return openEditors.values().stream()
                .filter(e -> e.getComponent() == component)
                .findFirst()
                .orElse(null);
    }

    private void refreshTabTitle(CodeEditorPane editor) {
        int index = tabs.indexOfComponent(editor.getComponent());
        if (index >= 0) {
            tabs.setTitleAt(index, tabTitle(editor));
        }
    }

    private static String tabTitle(CodeEditorPane editor) {
        String name = editor.displayName();
        return editor.isDirty() ? name + " ●" : name;
    }

    private static String diffTabTitle(DiffTexts texts, boolean staged) {
        String name = Path.of(texts.path()).getFileName().toString();
        return (staged ? "Diff (staged): " : "Diff: ") + name;
    }
}
