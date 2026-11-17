package com.jcode.model;

import java.time.Instant;
import java.util.List;

/**
 * One commit row for the Git log view.
 */
public record GitCommitEntry(
        String id,
        String shortId,
        String shortMessage,
        String fullMessage,
        String author,
        Instant when,
        List<String> parentIds,
        boolean head,
        List<String> refs,
        /** ASCII/Unicode lane graph prefix, e.g. {@code │ ● }. */
        String graph
) {
    public String displayRefs() {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        return String.join(", ", refs);
    }

    public String graphOrEmpty() {
        return graph == null ? "" : graph;
    }
}
