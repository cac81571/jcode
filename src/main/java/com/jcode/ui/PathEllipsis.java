package com.jcode.ui;

/**
 * Ellipsis helpers for long paths.
 * Prefer keeping the path head and file name: {@code src/main/…/Foo.java}.
 */
public final class PathEllipsis {

    private PathEllipsis() {
    }

    /**
     * Truncate {@code path} to at most {@code maxChars}, keeping the start and the file name
     * (or last segment) with {@code …} in the middle.
     */
    public static String middle(String path, int maxChars) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        if (maxChars < 5 || path.length() <= maxChars) {
            return maxChars < 5 && path.length() > maxChars ? path.substring(0, maxChars) : path;
        }

        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (sep <= 0) {
            return charMiddle(path, maxChars);
        }

        char sepChar = path.charAt(sep);
        String fileName = path.substring(sep + 1);
        String dir = path.substring(0, sep);
        String marker = sepChar + "…" + sepChar;
        String tail = marker + fileName;

        if (tail.length() >= maxChars) {
            int keep = Math.max(1, maxChars - 1);
            return "…" + fileName.substring(Math.max(0, fileName.length() - keep));
        }

        int headBudget = maxChars - tail.length();
        String head = dir;
        if (head.length() > headBudget) {
            head = dir.substring(0, headBudget);
            int cut = Math.max(head.lastIndexOf('/'), head.lastIndexOf('\\'));
            if (cut > 0) {
                head = head.substring(0, cut);
            } else if (headBudget < 1) {
                head = "";
            }
        }
        if (head.isEmpty()) {
            return "…" + sepChar + fileName;
        }
        return head + tail;
    }

    private static String charMiddle(String path, int maxChars) {
        int keep = maxChars - 1;
        int head = keep / 2;
        int tail = keep - head;
        return path.substring(0, head) + "…" + path.substring(path.length() - tail);
    }
}
