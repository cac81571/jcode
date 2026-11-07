package com.jcode.model;

/**
 * Old / new file contents for a colored diff view.
 */
public record DiffTexts(String path, String oldText, String newText) {
}
