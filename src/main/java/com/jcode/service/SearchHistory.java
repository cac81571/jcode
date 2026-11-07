package com.jcode.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Persists recent search keywords (java.util.prefs).
 */
public final class SearchHistory {

    private static final int MAX = 20;
    private static final String SEP = "\u001e";
    private static final Preferences PREFS = Preferences.userNodeForPackage(SearchHistory.class);
    private static final String KEY = "search.keywords";

    private SearchHistory() {
    }

    public static void remember(String query) {
        if (query == null) {
            return;
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(trimmed);
        for (String item : load()) {
            if (!item.equals(trimmed)) {
                set.add(item);
            }
        }
        save(set.stream().limit(MAX).collect(Collectors.toCollection(ArrayList::new)));
    }

    public static List<String> all() {
        return List.copyOf(load());
    }

    public static void clear() {
        PREFS.remove(KEY);
    }

    private static List<String> load() {
        String raw = PREFS.get(KEY, "");
        if (raw.isBlank()) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();
        for (String part : raw.split(SEP, -1)) {
            if (!part.isBlank()) {
                list.add(part);
            }
        }
        return list;
    }

    private static void save(List<String> values) {
        PREFS.put(KEY, String.join(SEP, values));
    }
}
