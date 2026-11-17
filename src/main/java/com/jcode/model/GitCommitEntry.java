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
        List<CommitGraphCell> graphCells,
        int commitLane
) {
    public String displayRefs() {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        return String.join(", ", refs);
    }

    public List<CommitGraphCell> graphCellsOrEmpty() {
        return graphCells == null ? List.of() : graphCells;
    }

    public String graphPlain() {
        if (graphCells == null || graphCells.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CommitGraphCell cell : graphCells) {
            sb.append(cell.symbol()).append(' ');
        }
        return sb.toString();
    }
}
