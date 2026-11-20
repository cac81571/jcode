package com.jcode.ui.search;

import com.jcode.model.ReplaceResult;
import com.jcode.model.SearchMatch;
import com.jcode.ui.search.SearchSidebar.ActiveFile;
import com.jcode.ui.search.SearchSidebar.SearchEditorRequest;
import com.jcode.ui.search.SearchSidebar.SearchTableRequest;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Several independent workspace searches inside the Search activity.
 * Each search keeps its own query, filters, and result list.
 */
public final class SearchTabs extends JPanel {

    @FunctionalInterface
    public interface MatchHandler {
        void open(SearchSidebar source, Path path, SearchMatch match);
    }

    private static final int TITLE_MAX = 24;

    private final JTabbedPane tabs = new JTabbedPane();
    private final Supplier<Path> workspaceRoot;
    private int nextId = 1;
    private Supplier<ActiveFile> activeFile = () -> null;
    private MatchHandler onOpenMatch = (source, path, match) -> {
    };
    private Consumer<SearchEditorRequest> onOpenInEditor = request -> {
    };
    private Consumer<SearchTableRequest> onOpenTable = request -> {
    };
    private Consumer<ReplaceResult> onReplaced = result -> {
    };

    public SearchTabs(Supplier<Path> workspaceRoot) {
        super(new BorderLayout());
        this.workspaceRoot = workspaceRoot;
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseCallback",
                (BiConsumer<JTabbedPane, Integer>) (pane, index) -> closeAt(index));
        tabs.putClientProperty("JTabbedPane.maximumTabWidth", 180);

        JButton add = new JButton("+");
        add.setToolTipText("新しい検索");
        add.setFocusable(false);
        add.setMargin(new Insets(2, 8, 2, 8));
        add.addActionListener(e -> addSearch());
        tabs.putClientProperty("JTabbedPane.trailingComponent", add);

        add(tabs, BorderLayout.CENTER);
        addSearch(false);
    }

    /** Points the selected search at {@code relativeDir} (workspace-relative, empty for the root). */
    public void searchInDirectory(String relativeDir) {
        SearchSidebar current = selected();
        if (current != null) {
            current.searchInDirectory(relativeDir);
        }
    }

    public void focusSearch() {
        SearchSidebar current = selected();
        if (current != null) {
            current.focusSearch();
        }
    }

    public void addSearch() {
        addSearch(true);
    }

    public void setActiveFileSupplier(Supplier<ActiveFile> activeFile) {
        this.activeFile = activeFile != null ? activeFile : () -> null;
    }

    public void setOnOpenMatch(MatchHandler onOpenMatch) {
        this.onOpenMatch = onOpenMatch != null ? onOpenMatch : (source, path, match) -> {
        };
    }

    public void setOnOpenInEditor(Consumer<SearchEditorRequest> onOpenInEditor) {
        this.onOpenInEditor = onOpenInEditor != null ? onOpenInEditor : request -> {
        };
    }

    public void setOnOpenTable(Consumer<SearchTableRequest> onOpenTable) {
        this.onOpenTable = onOpenTable != null ? onOpenTable : request -> {
        };
    }

    public void setOnReplaced(Consumer<ReplaceResult> onReplaced) {
        this.onReplaced = onReplaced != null ? onReplaced : result -> {
        };
    }

    private void addSearch(boolean focus) {
        String id = Integer.toString(nextId++);
        SearchSidebar sidebar = new SearchSidebar(workspaceRoot);
        sidebar.setSessionKey(id);
        wire(sidebar);
        String title = defaultTitle(id);
        tabs.addTab(title, sidebar);
        int index = tabs.indexOfComponent(sidebar);
        tabs.setToolTipTextAt(index, title);
        tabs.setSelectedIndex(index);
        if (focus) {
            SwingUtilities.invokeLater(sidebar::focusSearch);
        }
    }

    private void wire(SearchSidebar sidebar) {
        sidebar.setActiveFileSupplier(() -> activeFile.get());
        sidebar.setOnOpenMatch((path, match) -> onOpenMatch.open(sidebar, path, match));
        sidebar.setOnOpenInEditor(request -> onOpenInEditor.accept(request));
        sidebar.setOnOpenTable(request -> onOpenTable.accept(request));
        sidebar.setOnReplaced(result -> onReplaced.accept(result));
        sidebar.setOnSearched(query -> rename(sidebar, query));
    }

    private void closeAt(int index) {
        if (index < 0 || index >= tabs.getTabCount()) {
            return;
        }
        if (tabs.getTabCount() == 1) {
            addSearch(true);
        }
        tabs.remove(index);
    }

    private void rename(SearchSidebar sidebar, String query) {
        int index = tabs.indexOfComponent(sidebar);
        if (index < 0) {
            return;
        }
        String title = titleFor(sidebar.sessionKey(), query);
        tabs.setTitleAt(index, title);
        String tip = query == null || query.isBlank() ? title : query;
        tabs.setToolTipTextAt(index, tip);
    }

    private SearchSidebar selected() {
        Component component = tabs.getSelectedComponent();
        return component instanceof SearchSidebar sidebar ? sidebar : null;
    }

    private static String defaultTitle(String id) {
        return "検索 " + id;
    }

    private static String titleFor(String id, String query) {
        if (query == null || query.isBlank()) {
            return defaultTitle(id);
        }
        String oneLine = query.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= TITLE_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, TITLE_MAX - 1) + "…";
    }
}
