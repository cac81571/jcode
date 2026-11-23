package com.jcode.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextTypesTest {

    private Preferences node;

    @BeforeEach
    void isolatePreferences() {
        node = Preferences.userRoot().node("com/jcode/test/SearchTextTypes/" + UUID.randomUUID());
        SearchTextTypes.usePreferences(node);
    }

    @AfterEach
    void restorePreferences() throws BackingStoreException {
        SearchTextTypes.usePreferences(null);
        node.removeNode();
    }

    @Test
    void defaultsIncludeXhtml() {
        assertTrue(SearchTextTypes.extensions().contains("xhtml"));
        assertTrue(SearchTextTypes.extensions().contains("html"));
        assertTrue(SearchService.isLikelyText(Path.of("orderList.xhtml")));
        assertFalse(SearchService.isLikelyText(Path.of("notes.php")));
    }

    @Test
    void saveAcceptsDottedAndGlobForms() {
        assertTrue(SearchTextTypes.save("*.XHTML, .Java java").isEmpty());

        assertEquals(java.util.List.of("xhtml", "java"), SearchTextTypes.extensions());
        assertTrue(SearchService.isLikelyText(Path.of("page.xhtml")));
        assertFalse(SearchService.isLikelyText(Path.of("App.kt")));
    }

    @Test
    void rejectedTokensAreNotSaved() {
        SearchTextTypes.save("java");

        assertEquals(java.util.List.of("src/*.xhtml"), SearchTextTypes.save("xhtml, src/*.xhtml").stream().toList());
        assertEquals(java.util.List.of("java"), SearchTextTypes.extensions());
    }

    @Test
    void savingDefaultsClearsTheCustomList() {
        SearchTextTypes.save("xhtml");
        assertTrue(SearchTextTypes.save(SearchTextTypes.format(SearchTextTypes.defaults())).isEmpty());

        assertEquals(SearchTextTypes.defaults(), SearchTextTypes.extensions());
        assertTrue(SearchService.isLikelyText(Path.of("orderList.xhtml")));
    }

    @Test
    void resetRestoresDefaults() {
        SearchTextTypes.save("xhtml");
        SearchTextTypes.reset();

        assertEquals(SearchTextTypes.defaults(), SearchTextTypes.extensions());
    }
}
