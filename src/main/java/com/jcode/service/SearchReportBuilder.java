package com.jcode.service;

import com.jcode.model.SearchFileGroup;
import com.jcode.model.SearchMatch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Builds a ripgrep / VS Code Search Editor style report with ±N context lines.
 */
public final class SearchReportBuilder {

    private SearchReportBuilder() {
    }

    public static String build(
            String query,
            Path workspaceRoot,
            List<SearchFileGroup> groups,
            int contextLines
    ) {
        int hits = groups.stream().mapToInt(SearchFileGroup::hitCount).sum();
        StringBuilder out = new StringBuilder();
        out.append(hits).append(" results - ").append(groups.size()).append(" files");
        if (query != null && !query.isBlank()) {
            out.append("    query: ").append(query);
        }
        if (contextLines > 0) {
            out.append("    context: ±").append(contextLines);
        }
        out.append('\n');

        for (SearchFileGroup group : groups) {
            out.append('\n');
            out.append(displayPath(group.file(), workspaceRoot)).append(":\n");
            try {
                appendFileSection(out, group, Math.max(0, contextLines));
            } catch (IOException ex) {
                out.append("  (could not read file: ").append(ex.getMessage()).append(")\n");
            }
        }
        return out.toString();
    }

    /**
     * One table row: a hit line or a ±N context line around a hit.
     *
     * @param match the first hit on this line, or null for a context line
     * @param spans matched ranges in {@code text}, each {@code [start, end)} with end exclusive
     */
    public record TableRow(
            Path file,
            int lineNumber,
            String text,
            boolean hit,
            SearchMatch match,
            int[][] spans
    ) {
        public TableRow {
            spans = spans == null ? new int[0][] : spans;
        }
    }

    /** Hit lines plus ±context lines, in file order. */
    public static List<TableRow> tableRows(List<SearchMatch> matches, int contextLines) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        int context = Math.max(0, contextLines);
        Map<Path, List<SearchMatch>> byFile = new LinkedHashMap<>();
        for (SearchMatch match : matches) {
            byFile.computeIfAbsent(match.file(), k -> new ArrayList<>()).add(match);
        }
        List<TableRow> rows = new ArrayList<>();
        for (Map.Entry<Path, List<SearchMatch>> entry : byFile.entrySet()) {
            rows.addAll(rowsForFile(entry.getKey(), entry.getValue(), context));
        }
        return rows;
    }

    private static List<TableRow> rowsForFile(Path file, List<SearchMatch> matches, int context) {
        Map<Integer, List<SearchMatch>> hitsByLine = new LinkedHashMap<>();
        for (SearchMatch match : matches) {
            hitsByLine.computeIfAbsent(match.lineNumber(), k -> new ArrayList<>()).add(match);
        }
        List<String> lines;
        try {
            String content = SearchService.readText(file);
            lines = List.of((content == null ? "" : content).split("\n", -1));
        } catch (IOException ex) {
            List<TableRow> fallback = new ArrayList<>();
            for (SearchMatch match : matches) {
                String text = shownText(match.lineText());
                fallback.add(new TableRow(file, match.lineNumber(), text, true, match, spansFor(text, List.of(match))));
            }
            return fallback;
        }
        List<TableRow> rows = new ArrayList<>();
        for (int[] range : mergeRanges(new TreeSet<>(hitsByLine.keySet()), context, lines.size())) {
            for (int lineNo = range[0]; lineNo <= range[1]; lineNo++) {
                String text = shownText(lineNo <= lines.size() ? lines.get(lineNo - 1) : "");
                List<SearchMatch> hits = hitsByLine.get(lineNo);
                SearchMatch first = hits == null || hits.isEmpty() ? null : hits.getFirst();
                rows.add(new TableRow(file, lineNo, text, first != null, first, spansFor(text, hits)));
            }
        }
        return rows;
    }

    private static String shownText(String text) {
        if (text == null) {
            return "";
        }
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.replace('\t', ' ');
    }

    /** Match columns are 1-based, and the end column is exclusive. */
    private static int[][] spansFor(String text, List<SearchMatch> hits) {
        if (text == null || hits == null || hits.isEmpty()) {
            return new int[0][];
        }
        int length = text.length();
        List<int[]> spans = new ArrayList<>();
        for (SearchMatch hit : hits) {
            int start = Math.max(0, hit.startColumn() - 1);
            int end = Math.min(length, Math.max(start, hit.endColumn() - 1));
            if (start < end) {
                spans.add(new int[]{start, end});
            }
        }
        if (spans.isEmpty()) {
            return new int[0][];
        }
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        int[] current = spans.getFirst();
        for (int i = 1; i < spans.size(); i++) {
            int[] next = spans.get(i);
            if (next[0] <= current[1]) {
                current[1] = Math.max(current[1], next[1]);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged.toArray(int[][]::new);
    }

    private static void appendFileSection(StringBuilder out, SearchFileGroup group, int context)
            throws IOException {
        String content = SearchService.readText(group.file());
        if (content == null) {
            content = "";
        }
        List<String> lines = List.of(content.split("\n", -1));
        Set<Integer> hitLines = group.matches().stream()
                .map(SearchMatch::lineNumber)
                .collect(Collectors.toCollection(TreeSet::new));

        List<int[]> ranges = mergeRanges(hitLines, context, lines.size());
        boolean first = true;
        for (int[] range : ranges) {
            if (!first) {
                out.append('\n');
            }
            first = false;
            for (int lineNo = range[0]; lineNo <= range[1]; lineNo++) {
                String text = lineNo <= lines.size() ? lines.get(lineNo - 1) : "";
                if (text.endsWith("\r")) {
                    text = text.substring(0, text.length() - 1);
                }
                boolean hit = hitLines.contains(lineNo);
                out.append(String.format(java.util.Locale.ROOT, "%6d%s %s%n",
                        lineNo, hit ? ":" : " ", text));
            }
        }
    }

    /**
     * Expand each hit by ±context and merge overlapping / adjacent ranges.
     */
    public static List<int[]> mergeRanges(Set<Integer> hitLines, int context, int lineCount) {
        List<int[]> raw = new ArrayList<>();
        for (int hit : hitLines) {
            int from = Math.max(1, hit - context);
            int to = Math.min(lineCount, hit + context);
            raw.add(new int[]{from, to});
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        raw.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        int[] cur = raw.getFirst();
        for (int i = 1; i < raw.size(); i++) {
            int[] next = raw.get(i);
            if (next[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);
        return merged;
    }

    private static String displayPath(Path file, Path workspaceRoot) {
        if (workspaceRoot != null && file.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(file).toString();
        }
        return file.toString();
    }
}
