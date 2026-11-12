package com.jcode.service;

import com.jcode.model.ReplaceResult;
import com.jcode.model.SearchMatch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchService {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", ".vscode", "out", "dist", ".cursor"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "xml", "md", "txt", "json", "yml", "yaml", "properties",
            "gradle", "kts", "html", "css", "js", "ts", "tsx", "jsx", "py", "go",
            "rs", "c", "h", "cpp", "hpp", "cs", "sql", "sh", "bat", "ps1", "gitignore",
            "toml", "ini", "cfg", "svg", "fxml", "csv", "tsv", "log"
    );

    /** Prefer UTF-8; fall back for Japanese Windows CSVs (Shift_JIS / MS932). */
    private static final List<Charset> READ_CHARSETS = buildReadCharsets();

    private SearchService() {
    }

    public static List<SearchMatch> findInText(
            String text,
            Path file,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) {
        List<SearchMatch> matches = new ArrayList<>();
        if (query == null || query.isEmpty() || text == null) {
            return matches;
        }

        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String searchLine = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            Matcher matcher = pattern.matcher(searchLine);
            while (matcher.find()) {
                matches.add(new SearchMatch(file, i + 1, matcher.start() + 1, matcher.end() + 1, searchLine));
            }
        }
        return matches;
    }

    public static List<SearchMatch> findInWorkspace(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) throws IOException {
        return findInWorkspace(root, query, matchCase, wholeWord, regex, null, null);
    }

    public static List<SearchMatch> findInWorkspace(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String filesToInclude,
            String filesToExclude
    ) throws IOException {
        List<SearchMatch> matches = new ArrayList<>();
        if (root == null || !Files.isDirectory(root) || query == null || query.isEmpty()) {
            return matches;
        }

        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        FilePathFilter pathFilter = FilePathFilter.parse(filesToInclude, filesToExclude);
        Path absoluteRoot = root.toAbsolutePath().normalize();

        Files.walkFileTree(absoluteRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(absoluteRoot) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (pathFilter.skipDirectory(absoluteRoot, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > 2_000_000) {
                    return FileVisitResult.CONTINUE;
                }
                if (!pathFilter.accept(absoluteRoot, file)) {
                    return FileVisitResult.CONTINUE;
                }
                // When include globs are set, trust them; otherwise keep text-extension filter
                if (!pathFilter.hasIncludes() && !isLikelyText(file)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String content = readText(file);
                    if (content != null) {
                        matches.addAll(findInText(content, file, pattern));
                    }
                } catch (IOException ignored) {
                    // binary / unreadable
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return matches;
    }

    public static String replaceInText(
            String text,
            String query,
            String replacement,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            boolean replaceAll
    ) {
        if (query == null || query.isEmpty() || text == null) {
            return text;
        }
        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        Matcher matcher = pattern.matcher(text);
        String safeReplacement = Matcher.quoteReplacement(replacement == null ? "" : replacement);
        return replaceAll ? matcher.replaceAll(safeReplacement) : matcher.replaceFirst(safeReplacement);
    }

    /**
     * Replace all matches in the given files. Returns how many files / occurrences changed.
     */
    public static ReplaceResult replaceInFiles(
            Collection<Path> files,
            String query,
            String replacement,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) throws IOException {
        if (files == null || files.isEmpty() || query == null || query.isEmpty()) {
            return new ReplaceResult(0, 0, List.of());
        }
        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        String safeReplacement = Matcher.quoteReplacement(replacement == null ? "" : replacement);
        List<Path> changed = new ArrayList<>();
        int totalReplacements = 0;

        for (Path file : new LinkedHashSet<>(files)) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            DecodedText decoded = readTextWithCharset(file);
            if (decoded == null) {
                continue;
            }
            String original = decoded.text();
            Matcher matcher = pattern.matcher(original);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count == 0) {
                continue;
            }
            String updated = pattern.matcher(original).replaceAll(safeReplacement);
            if (!original.equals(updated)) {
                Files.writeString(file, updated, decoded.charset());
                changed.add(file.toAbsolutePath().normalize());
                totalReplacements += count;
            }
        }
        return new ReplaceResult(changed.size(), totalReplacements, List.copyOf(changed));
    }

    /** Read file as text, trying UTF-8 then common Japanese Windows encodings. */
    public static String readText(Path file) throws IOException {
        DecodedText decoded = readTextWithCharset(file);
        return decoded == null ? null : decoded.text();
    }

    private static DecodedText readTextWithCharset(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new DecodedText(
                    new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
        for (Charset charset : READ_CHARSETS) {
            try {
                CharsetDecoder decoder = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
                return new DecodedText(text, charset);
            } catch (CharacterCodingException ignored) {
                // try next charset
            }
        }
        // Last resort: replace malformed bytes so search still works
        return new DecodedText(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static List<Charset> buildReadCharsets() {
        List<Charset> list = new ArrayList<>();
        list.add(StandardCharsets.UTF_8);
        for (String name : List.of("windows-31j", "Shift_JIS", "MS932", Charset.defaultCharset().name())) {
            try {
                Charset cs = Charset.forName(name);
                if (!list.contains(cs)) {
                    list.add(cs);
                }
            } catch (Exception ignored) {
                // charset unavailable on this JVM
            }
        }
        return List.copyOf(list);
    }

    private record DecodedText(String text, Charset charset) {
    }

    private static List<SearchMatch> findInText(String text, Path file, Pattern pattern) {
        List<SearchMatch> matches = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String searchLine = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            Matcher matcher = pattern.matcher(searchLine);
            while (matcher.find()) {
                matches.add(new SearchMatch(file, i + 1, matcher.start() + 1, matcher.end() + 1, searchLine));
            }
        }
        return matches;
    }

    private static Pattern compile(String query, boolean matchCase, boolean wholeWord, boolean regex) {
        String patternText = regex ? query : Pattern.quote(query);
        if (wholeWord) {
            patternText = "\\b" + patternText + "\\b";
        }
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(patternText, flags);
    }

    private static boolean isLikelyText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return name.equals("Dockerfile") || name.equals("Makefile") || name.equals("LICENSE");
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
    }
}
