package com.jcode.service;

import com.jcode.model.SearchFileGroup;
import com.jcode.model.SearchMatch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
