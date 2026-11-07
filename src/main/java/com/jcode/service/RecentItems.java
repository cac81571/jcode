package com.jcode.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Remembers recently opened folders and files (java.util.prefs).
 */
public final class RecentItems {

    private static final int MAX = 12;
    private static final Preferences PREFS = Preferences.userNodeForPackage(RecentItems.class);
    private static final String KEY_FOLDERS = "recent.folders";
    private static final String KEY_FILES = "recent.files";
    private static final String KEY_LAST_FOLDER = "recent.lastFolder";

    private RecentItems() {
    }

    public static void rememberFolder(Path folder) {
        if (folder == null) {
            return;
        }
        Path abs = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(abs)) {
            return;
        }
        List<String> list = prepend(load(KEY_FOLDERS), abs.toString());
        save(KEY_FOLDERS, list);
        PREFS.put(KEY_LAST_FOLDER, abs.toString());
    }

    public static void rememberFile(Path file) {
        if (file == null) {
            return;
        }
        Path abs = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            return;
        }
        save(KEY_FILES, prepend(load(KEY_FILES), abs.toString()));
    }

    public static List<Path> folders() {
        return existing(load(KEY_FOLDERS), true);
    }

    public static List<Path> files() {
        return existing(load(KEY_FILES), false);
    }

    public static Path lastFolder() {
        String value = PREFS.get(KEY_LAST_FOLDER, "");
        if (value.isBlank()) {
            return null;
        }
        Path path = Path.of(value);
        return Files.isDirectory(path) ? path.toAbsolutePath().normalize() : null;
    }

    public static void clear() {
        PREFS.remove(KEY_FOLDERS);
        PREFS.remove(KEY_FILES);
        PREFS.remove(KEY_LAST_FOLDER);
    }

    private static List<String> prepend(List<String> current, String next) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(next);
        for (String item : current) {
            if (!item.equalsIgnoreCase(next)) {
                set.add(item);
            }
        }
        return set.stream().limit(MAX).collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<String> load(String key) {
        String raw = PREFS.get(key, "");
        if (raw.isBlank()) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            if (!part.isBlank()) {
                list.add(part);
            }
        }
        return list;
    }

    private static void save(String key, List<String> values) {
        PREFS.put(key, String.join("|", values));
    }

    private static List<Path> existing(List<String> stored, boolean directories) {
        List<Path> result = new ArrayList<>();
        for (String value : stored) {
            Path path = Path.of(value);
            if (directories ? Files.isDirectory(path) : Files.isRegularFile(path)) {
                result.add(path.toAbsolutePath().normalize());
            }
        }
        return result;
    }
}
