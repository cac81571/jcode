package com.jcode.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * Extensions searched when Include(file) is empty. Defaults include {@code xhtml}.
 * A saved list in preferences replaces the defaults until it is cleared.
 */
public final class SearchTextTypes {

    private static final String KEY = "extensions";
    private static final String UNSET = "\u0000";
    private static final Preferences USER = Preferences.userNodeForPackage(SearchTextTypes.class);
    private static volatile Preferences active = USER;

    private static final List<String> DEFAULT_EXTENSIONS = List.of(
            "java", "kt", "xml", "md", "txt", "json", "yml", "yaml", "properties",
            "gradle", "kts", "html", "xhtml", "css", "js", "ts", "tsx", "jsx", "py", "go",
            "rs", "c", "h", "cpp", "hpp", "cs", "sql", "sh", "bat", "ps1", "gitignore",
            "toml", "ini", "cfg", "svg", "fxml", "csv", "tsv", "log"
    );

    private SearchTextTypes() {
    }

    public static List<String> defaults() {
        return DEFAULT_EXTENSIONS;
    }

    /** Current list. The default list when nothing has been saved. */
    public static List<String> extensions() {
        String stored = active.get(KEY, UNSET);
        if (stored == null || UNSET.equals(stored)) {
            return DEFAULT_EXTENSIONS;
        }
        return parse(stored).accepted();
    }

    public static boolean contains(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        return extensions().contains(extension.toLowerCase(Locale.ROOT));
    }

    public static String format(List<String> extensions) {
        return String.join(", ", extensions);
    }

    /** Tokens that are not a single extension. */
    public static List<String> rejected(String raw) {
        return parse(raw).rejected();
    }

    public static boolean isEmpty(String raw) {
        return parse(raw).accepted().isEmpty();
    }

    /**
     * Stores a custom list. Tokens may be {@code xhtml}, {@code .xhtml}, or {@code *.xhtml}.
     *
     * @return tokens that are not plain extensions; nothing is saved when this is non-empty
     */
    public static List<String> save(String raw) {
        Parsed parsed = parse(raw);
        if (!parsed.rejected().isEmpty()) {
            return parsed.rejected();
        }
        if (parsed.accepted().equals(DEFAULT_EXTENSIONS)) {
            active.remove(KEY);
            return List.of();
        }
        String stored = String.join(",", parsed.accepted());
        if (stored.length() > Preferences.MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("拡張子の指定が長すぎます。");
        }
        active.put(KEY, stored);
        return List.of();
    }

    /** Forgets a custom list so the defaults are used again. */
    public static void reset() {
        active.remove(KEY);
    }

    static Parsed parse(String raw) {
        LinkedHashSet<String> accepted = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return new Parsed(List.of(), List.of());
        }
        for (String token : raw.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            String extension = normalize(token);
            if (extension == null) {
                rejected.add(token.trim());
            } else {
                accepted.add(extension);
            }
        }
        return new Parsed(List.copyOf(accepted), List.copyOf(rejected));
    }

    /** {@code null} when the token is not a single extension. */
    static String normalize(String token) {
        String value = token.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("*.")) {
            value = value.substring(2);
        }
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '+' || c == '#')) {
                return null;
            }
        }
        return value;
    }

    /** Test hook. {@code null} restores the user preferences node. */
    static void usePreferences(Preferences preferences) {
        active = preferences == null ? USER : preferences;
    }

    record Parsed(List<String> accepted, List<String> rejected) {
    }
}
