package com.jcode.model;

import java.nio.file.Path;

public record SearchMatch(
        Path file,
        int lineNumber,
        int startColumn,
        int endColumn,
        String lineText
) {
    public String displayPath(Path workspaceRoot) {
        if (workspaceRoot != null && file.startsWith(workspaceRoot)) {
            return workspaceRoot.relativize(file).toString().replace('\\', '/');
        }
        return file.toString().replace('\\', '/');
    }
}
