package com.jcode.model;

import java.nio.file.Path;
import java.util.List;

public record ReplaceResult(int filesChanged, int replacements, List<Path> changedFiles) {
}
