package com.jcode.model;

/**
 * Old / new file contents for a colored diff view.
 */
public record DiffTexts(String path, String oldText, String newText, String oldRev, String newRev) {
    public DiffTexts {
        oldRev = oldRev == null ? "" : oldRev;
        newRev = newRev == null ? "" : newRev;
    }

    public DiffTexts(String path, String oldText, String newText) {
        this(path, oldText, newText, "", "");
    }
}
