package com.jcode.model;

/**
 * One column in a HISTORY lane graph.
 */
public record CommitGraphCell(int lane, Kind kind) {

    public enum Kind {
        EMPTY,
        PIPE,
        COMMIT,
        MERGE
    }

    public char symbol() {
        return switch (kind) {
            case EMPTY -> ' ';
            case PIPE -> '│';
            case COMMIT -> '●';
            case MERGE -> '◆';
        };
    }
}
