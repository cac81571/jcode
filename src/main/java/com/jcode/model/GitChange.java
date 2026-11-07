package com.jcode.model;

import org.eclipse.jgit.diff.DiffEntry;

public record GitChange(
        String path,
        DiffEntry.ChangeType changeType,
        boolean staged
) {
    public String statusLabel() {
        return switch (changeType) {
            case ADD -> "A";
            case MODIFY -> "M";
            case DELETE -> "D";
            case RENAME -> "R";
            case COPY -> "C";
            default -> "?";
        };
    }
}
