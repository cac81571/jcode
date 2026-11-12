package com.jcode.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Persists recent search / filter strings (java.util.prefs).
 */
public final class SearchHistory {

    public enum Kind {
        QUERY("search.keywords"),
        INCLUDE("search.include"),
        EXCLUDE("search.exclude");

        private final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    private static final int MAX = 20;
    private static final String SEP = "\u001e";
    private static final Preferences PREFS = Preferences.userNodeForPackage(SearchHistory.class);

    private SearchHistory() {
    }

    public static void remember(String query) {
        remember(Kind.QUERY, query);
    }

    public static List<String> all() {
        return all(Kind.QUERY);
    }

    public static void clear() {
        clear(Kind.QUERY);
    }

    public static void remember(Kind kind, String value) {
        if (kind == null || value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(trimmed);
        for (String item : load(kind)) {
            if (!item.equals(trimmed)) {
                set.add(item);
            }
        }
        save(kind, set.stream().limit(MAX).collect(Collectors.toCollection(ArrayList::new)));
    }

    public static List<String> all(Kind kind) {
        return List.copyOf(load(kind));
    }

    public static void clear(Kind kind) {
        if (kind != null) {
            PREFS.remove(kind.key);
        }
    }

    private static List<String> load(Kind kind) {
        String raw = PREFS.get(kind.key, "");
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

    private static void save(Kind kind, List<String> values) {
        PREFS.put(kind.key, String.join(SEP, values));
    }
}
