package com.jcode.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code rg --json} argument list from the search sidebar options.
 * Globs are a superset of {@link FilePathFilter}; matches are filtered again in Java
 * so include / exclude semantics stay the same.
 * <p>
 * The pattern is read from {@code patternFile} ({@code -f}) rather than {@code -e},
 * so Windows {@code CreateProcess} quoting cannot strip or break embedded {@code "}
 * characters in the query.
 */
final class RipgrepCommand {

    private RipgrepCommand() {
    }

    static List<String> build(
            Path executable,
            WorkspaceSearch.Request request,
            String encodingOrNull,
            Path patternFile
    ) {
        List<String> args = new ArrayList<>();
        args.add(executable.toString());
        args.add("--json");
        args.add("--no-config");
        args.add("--color=never");
        args.add("--no-ignore");
        args.add("--hidden");
        args.add("--max-filesize");
        args.add(Long.toString(SearchService.MAX_FILE_BYTES));
        if (RipgrepLocator.isWindowsOs()) {
            args.add("--glob-case-insensitive");
        }
        if (request.matchCase()) {
            args.add("--case-sensitive");
        } else {
            args.add("--ignore-case");
        }
        if (!request.regex()) {
            args.add("--fixed-strings");
        }
        if (request.wholeWord()) {
            args.add("--word-regexp");
        }
        if (encodingOrNull != null && !encodingOrNull.isBlank()) {
            args.add("--encoding");
            args.add(encodingOrNull);
        }
        addGlobs(args, request);
        args.add("-f");
        args.add(patternFile.toAbsolutePath().normalize().toString());
        args.add(request.root().toAbsolutePath().normalize().toString());
        return args;
    }

    private static void addGlobs(List<String> args, WorkspaceSearch.Request request) {
        List<String> fileIncludes = split(request.includeFiles());
        List<String> dirIncludes = split(request.includeDirs());
        List<String> positive = new ArrayList<>();
        if (fileIncludes.isEmpty() && dirIncludes.isEmpty()) {
            for (String ext : SearchService.textExtensions()) {
                positive.add("*." + ext);
            }
            positive.addAll(SearchService.specialTextFiles());
        } else if (dirIncludes.isEmpty()) {
            for (String pattern : fileIncludes) {
                positive.add(toRgGlob(pattern));
            }
        } else if (fileIncludes.isEmpty()) {
            for (String dir : dirIncludes) {
                positive.add(dirGlob(dir));
            }
        } else {
            for (String dir : dirIncludes) {
                String dirPattern = dirGlob(dir);
                for (String file : fileIncludes) {
                    positive.add(combine(dirPattern, toRgGlob(file)));
                }
            }
        }
        for (String glob : positive) {
            addGlob(args, glob);
        }
        for (String skip : SearchService.skipDirNames()) {
            addGlob(args, "!**/" + skip + "/**");
        }
        // File excludes stay in FilePathFilter. ripgrep's gitignore globs also match
        // dotfiles (*.txt excludes .hidden.txt) while the Java matcher does not.
        for (String dir : split(request.excludeDirs())) {
            addGlob(args, "!" + dirGlob(dir));
        }
    }

    private static void addGlob(List<String> args, String glob) {
        if (glob == null || glob.isBlank() || glob.equals("!")) {
            return;
        }
        args.add("--glob");
        args.add(glob);
    }

    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return parts;
        }
        for (String part : text.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /** File glob in ripgrep's gitignore syntax. A name without {@code /} matches in any directory. */
    static String toRgGlob(String raw) {
        String glob = stripEdges(raw);
        if (glob.isEmpty()) {
            return glob;
        }
        if (!glob.contains("/")) {
            return glob;
        }
        if (glob.startsWith("**/")) {
            return glob;
        }
        return "**/" + glob;
    }

    /** Directory pattern that matches files anywhere under that directory. */
    static String dirGlob(String raw) {
        String glob = stripEdges(raw);
        while (glob.startsWith("**/")) {
            glob = glob.substring(3);
        }
        while (glob.endsWith("/**")) {
            glob = glob.substring(0, glob.length() - 3);
        }
        if (glob.isEmpty()) {
            return "**/*";
        }
        return "**/" + glob + "/**";
    }

    private static String combine(String dirPattern, String filePattern) {
        String file = filePattern.startsWith("**/") ? filePattern.substring(3) : filePattern;
        if (dirPattern.endsWith("/**")) {
            return dirPattern + "/" + file;
        }
        return dirPattern + "/" + file;
    }

    private static String stripEdges(String raw) {
        if (raw == null) {
            return "";
        }
        String glob = raw.trim().replace('\\', '/');
        if (glob.startsWith("./")) {
            glob = glob.substring(2);
        }
        while (glob.endsWith("/")) {
            glob = glob.substring(0, glob.length() - 1);
        }
        return glob;
    }
}
