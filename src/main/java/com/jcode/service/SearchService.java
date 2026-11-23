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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchService {

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", ".vscode", "out", "dist", ".cursor"
    );

    /** Extensionless names treated as text when no include-file glob is set. */
    private static final Set<String> SPECIAL_TEXT_FILES = Set.of("Dockerfile", "Makefile", "LICENSE");

    /** Files larger than this are skipped (also passed to ripgrep as --max-filesize). */
    static final long MAX_FILE_BYTES = 2_000_000L;

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
        if (query == null || query.isEmpty() || text == null) {
            return List.of();
        }
        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        return findInText(text, file, pattern, query, matchCase, wholeWord, regex);
    }

    public static List<SearchMatch> findInWorkspace(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) throws IOException {
        return findInWorkspace(root, query, matchCase, wholeWord, regex, null, null, null, null);
    }

    public static List<SearchMatch> findInWorkspace(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs
    ) throws IOException {
        if (root == null || !Files.isDirectory(root) || query == null || query.isEmpty()) {
            return List.of();
        }

        return findInWorkspace(
                root, query, matchCase, wholeWord, regex,
                includeFiles, includeDirs, excludeFiles, excludeDirs, () -> false);
    }

    public static List<SearchMatch> findInWorkspace(
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs,
            BooleanSupplier cancelled
    ) throws IOException {
        if (root == null || !Files.isDirectory(root) || query == null || query.isEmpty()) {
            return List.of();
        }
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;

        Pattern pattern = compile(query, matchCase, wholeWord, regex);
        FilePathFilter pathFilter = FilePathFilter.parse(includeFiles, includeDirs, excludeFiles, excludeDirs);
        Path absoluteRoot = root.toAbsolutePath().normalize();

        List<Path> files = new ArrayList<>();
        Files.walkFileTree(absoluteRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (stop.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
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
                if (stop.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.size() > MAX_FILE_BYTES) {
                    return FileVisitResult.CONTINUE;
                }
                if (!pathFilter.accept(absoluteRoot, file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!pathFilter.hasFileIncludes() && !isLikelyText(file)) {
                    return FileVisitResult.CONTINUE;
                }
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        if (stop.getAsBoolean()) {
            return List.of();
        }

        ConcurrentLinkedQueue<SearchMatch> queue = new ConcurrentLinkedQueue<>();
        files.parallelStream().forEach(file -> {
            if (stop.getAsBoolean()) {
                return;
            }
            try {
                String content = readText(file);
                if (content == null || stop.getAsBoolean()) {
                    return;
                }
                List<SearchMatch> found = findInText(
                        content, file, pattern, query, matchCase, wholeWord, regex);
                if (!found.isEmpty()) {
                    queue.addAll(found);
                }
            } catch (IOException ignored) {
                // binary / unreadable
            }
        });
        if (stop.getAsBoolean()) {
            return List.of();
        }

        return sorted(queue);
    }

    private static List<SearchMatch> sorted(ConcurrentLinkedQueue<SearchMatch> queue) {
        List<SearchMatch> matches = new ArrayList<>(queue);
        matches.sort(Comparator
                .comparing((SearchMatch m) -> m.file().toString())
                .thenComparingInt(SearchMatch::lineNumber)
                .thenComparingInt(SearchMatch::startColumn));
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
        return decodeBytes(Files.readAllBytes(file));
    }

    /**
     * Decode file or line bytes. UTF-8 (with BOM stripped) wins when it is strict;
     * otherwise Windows-31J / Shift_JIS. Returns null when the buffer looks binary.
     */
    static DecodedText decodeBytes(byte[] bytes) {
        if (bytes == null || containsNul(bytes)) {
            return null;
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new DecodedText(
                    new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8);
        }
        for (Charset charset : READ_CHARSETS) {
            String text = decodeStrict(bytes, charset);
            if (text != null) {
                return new DecodedText(text, charset);
            }
        }
        // Last resort: replace malformed bytes so search still works
        return new DecodedText(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    static boolean isStrictUtf8(byte[] bytes) {
        if (bytes == null || containsNul(bytes)) {
            return false;
        }
        int offset = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        return decodeStrict(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8) != null;
    }

    /** Character length of {@code raw[0, byteOffset)} decoded with {@code charset}. */
    static int prefixCharLength(byte[] raw, int byteOffset, Charset charset) {
        if (raw == null || raw.length == 0 || byteOffset <= 0) {
            return 0;
        }
        int n = Math.min(byteOffset, raw.length);
        String strict = decodeStrict(raw, 0, n, charset);
        if (strict != null) {
            return strict.length();
        }
        return new String(raw, 0, n, charset).length();
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        return decodeStrict(bytes, 0, bytes.length, charset);
    }

    private static String decodeStrict(byte[] bytes, int offset, int length, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static boolean containsNul(byte[] bytes) {
        int limit = Math.min(bytes.length, 8_192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
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

    record DecodedText(String text, Charset charset) {
    }

    static Set<String> skipDirNames() {
        return SKIP_DIRS;
    }

    static Set<String> textExtensions() {
        return new LinkedHashSet<>(SearchTextTypes.extensions());
    }

    static Set<String> specialTextFiles() {
        return SPECIAL_TEXT_FILES;
    }

    private static List<SearchMatch> findInText(
            String text,
            Path file,
            Pattern pattern,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) {
        // Cheap reject for literal queries before line scanning / Matcher work
        if (!regex && !wholeWord && !mayContainLiteral(text, query, matchCase)) {
            return List.of();
        }

        List<SearchMatch> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher("");
        int lineStart = 0;
        int lineNumber = 1;
        final int length = text.length();
        while (lineStart <= length) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? length : nl;
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String searchLine = text.substring(lineStart, contentEnd);
            matcher.reset(searchLine);
            while (matcher.find()) {
                matches.add(new SearchMatch(
                        file, lineNumber, matcher.start() + 1, matcher.end() + 1, searchLine));
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
            lineNumber++;
        }
        return matches;
    }

    private static boolean mayContainLiteral(String text, String query, boolean matchCase) {
        if (query.isEmpty()) {
            return true;
        }
        if (matchCase) {
            return text.contains(query);
        }
        return indexOfIgnoreCase(text, query) >= 0;
    }

    private static int indexOfIgnoreCase(String text, String query) {
        final int textLen = text.length();
        final int queryLen = query.length();
        if (queryLen == 0) {
            return 0;
        }
        if (queryLen > textLen) {
            return -1;
        }
        char firstLower = Character.toLowerCase(query.charAt(0));
        char firstUpper = Character.toUpperCase(query.charAt(0));
        for (int i = 0; i <= textLen - queryLen; i++) {
            char c = text.charAt(i);
            if (c != firstLower && c != firstUpper) {
                continue;
            }
            if (text.regionMatches(true, i, query, 0, queryLen)) {
                return i;
            }
        }
        return -1;
    }

    private static Pattern compile(String query, boolean matchCase, boolean wholeWord, boolean regex) {
        String patternText = regex ? query : Pattern.quote(query);
        if (wholeWord) {
            patternText = "\\b" + patternText + "\\b";
        }
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(patternText, flags);
    }

    static boolean isLikelyText(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return SPECIAL_TEXT_FILES.contains(name);
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SearchTextTypes.contains(ext);
    }
}
