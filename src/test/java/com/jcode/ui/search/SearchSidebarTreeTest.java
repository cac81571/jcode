package com.jcode.ui.search;

import com.jcode.model.SearchMatch;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A search used to reload an empty tree (which collapses the hidden root) and then
 * expand file nodes while they were still leaves. {@code expandPath} ignores leaves
 * and does not open the root, so the model held every hit while {@code getRowCount()}
 * stayed 0. The table view reads the model, so it still showed the same results.
 */
class SearchSidebarTreeTest {

    @Test
    void streamedMatchesStayVisibleAfterEmptyReload() throws Exception {
        AtomicReference<SearchSidebar> sidebarRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> sidebarRef.set(new SearchSidebar(() -> Path.of("."))));
        SearchSidebar sidebar = sidebarRef.get();

        Path windowsFile = Path.of("C:\\work\\src\\Foo.java");
        Path otherFile = Path.of("src", "Bar.java").toAbsolutePath().normalize();
        List<SearchMatch> matches = List.of(
                new SearchMatch(windowsFile, 3, 1, 5, "File open"),
                new SearchMatch(windowsFile, 8, 2, 6, "    File name"),
                new SearchMatch(otherFile, 1, 1, 5, "File")
        );

        SwingUtilities.invokeAndWait(() -> {
            sidebar.resetResultTree();
            assertEquals(0, sidebar.visibleResultRows());
            assertEquals(0, sidebar.modelFileCount());

            sidebar.appendMatches(matches);

            assertEquals(2, sidebar.modelFileCount());
            assertTrue(sidebar.visibleResultRows() > 0,
                    "hidden root stayed collapsed; model has files but the tree shows no rows");
            sidebar.expandAllResults();
        });

        assertEquals(2, sidebar.modelFileCount());
        // 2 file rows + 3 match rows once every file node is expanded
        assertEquals(5, sidebar.visibleResultRows());
    }
}
