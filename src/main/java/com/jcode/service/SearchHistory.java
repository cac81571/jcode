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
        INCLUDE_FILE("search.include.file"),
        INCLUDE_DIR("search.include.dir"),
        EXCLUDE_FILE("search.exclude.file"),
        EXCLUDE_DIR("search.exclude.dir"),
        QUICK_OPEN_FOLDER("quickopen.folder"),
        QUICK_OPEN_EXTENSION("quickopen.extension");

        private final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    private static final int MAX = 20;
    private static final String SEP = "\u001e";
    private static final String UNSET = "\u0000";
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

    /** Remembers {@code value} as the value to restore next time, including blank. */
    public static void rememberLast(Kind kind, String value) {
        if (kind == null) {
            return;
        }
        String trimmed = value == null ? "" : value.trim();
        PREFS.put(lastKey(kind), trimmed);
        remember(kind, trimmed);
    }

    /** Last restored value. Falls back to the newest history entry when none was stored yet. */
    public static String last(Kind kind) {
        if (kind == null) {
            return "";
        }
        String stored = PREFS.get(lastKey(kind), UNSET);
        if (!UNSET.equals(stored)) {
            return stored;
        }
        List<String> items = load(kind);
        return items.isEmpty() ? "" : items.get(0);
    }

    public static void clear(Kind kind) {
        if (kind != null) {
            PREFS.remove(kind.key);
            PREFS.remove(lastKey(kind));
        }
    }

    private static String lastKey(Kind kind) {
        return kind.key + ".last";
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
