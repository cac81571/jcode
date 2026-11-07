package com.jcode.ui.search;

/**
 * Middle-ellipsis for long paths: {@code src/main/.../ui/search}.
 */
public final class PathEllipsis {

    private PathEllipsis() {
    }

    public static String middle(String path, int maxChars) {
        if (path == null || path.isEmpty() || path.length() <= maxChars) {
            return path == null ? "" : path;
        }
        if (maxChars < 5) {
            return path.substring(0, maxChars);
        }
        int keep = maxChars - 1; // room for '…'
        int head = keep / 2;
        int tail = keep - head;
        return path.substring(0, head) + "…" + path.substring(path.length() - tail);
    }
}
