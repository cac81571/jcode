package com.jcode.model;

import java.nio.file.Path;
import java.util.List;

public record SearchFileGroup(Path file, List<SearchMatch> matches) {

    public String fileName() {
        return file.getFileName().toString();
    }

    public int hitCount() {
        return matches.size();
    }

    public String relativeDir(Path workspaceRoot) {
        Path parent = file.getParent();
        if (parent == null) {
            return "";
        }
        if (workspaceRoot != null && parent.startsWith(workspaceRoot)) {
            String rel = workspaceRoot.relativize(parent).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        }
        return parent.toString().replace('\\', '/');
    }
}
