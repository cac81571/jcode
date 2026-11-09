package com.jcode.model;

/**
 * One stash entry ({@code stash@{n}}).
 */
public record GitStashEntry(
        int index,
        String ref,
        String shortId,
        String message
) {
}
