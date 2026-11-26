package com.jcode.service;

import com.jcode.model.SearchMatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs ripgrep and turns its JSON Lines stream into {@link SearchMatch} batches.
 * The calling thread blocks until the process ends; it must not be the EDT.
 */
final class RipgrepSearch {

    /** Encodings tried, in order, for the Shift_JIS / MS932 pass. */
    private static final List<String> SJIS_ENCODINGS = List.of("windows-31j", "ms932", "shift_jis");

    private static volatile int sjisEncodingIndex;

    private RipgrepSearch() {
    }

    static WorkspaceSearch.Outcome execute(
            Path executable,
            WorkspaceSearch.Request request,
            WorkspaceSearch.Handle handle,
            Consumer<List<SearchMatch>> onBatch
    ) throws IOException {
        if (request.root() == null || !Files.isDirectory(request.root())
                || request.query() == null || request.query().isEmpty()) {
            return WorkspaceSearch.Outcome.ripgrep("ripgrep");
        }
        Path root = request.root().toAbsolutePath().normalize();
        FilePathFilter filter = FilePathFilter.parse(
                request.includeFiles(), request.includeDirs(), request.excludeFiles(), request.excludeDirs());
        boolean asciiLiteral = !request.regex() && isAscii(request.query());
        Emitter emitter = new Emitter(onBatch);
        Set<Path> utf8Files = new HashSet<>();
        Set<Path> notUtf8Files = new HashSet<>();
        Set<String> seen = new HashSet<>();
        // Byte-level matches are correct for literals (ASCII is identical in UTF-8 and Shift_JIS;
        // the bytes field is decoded with the file charset). Regex is character-based, so invalid
        // UTF-8 lines are left to the Windows-31J pass instead of being matched as raw bytes.
        boolean acceptRawLines = !request.regex();

        Pass first = runPass(
                executable, request, null, handle, root, filter, emitter, seen, utf8Files, notUtf8Files, acceptRawLines);
        if (first.cancelled()) {
            emitter.flush();
            return WorkspaceSearch.Outcome.cancelled(WorkspaceSearch.Engine.RIPGREP);
        }
        if (first.failure() != null) {
            emitter.flush();
            return WorkspaceSearch.Outcome.failed(first.failure());
        }

        String suffix = "ripgrep";
        if (!asciiLiteral) {
            Pass second = runSjisPass(
                    executable, request, handle, root, filter, emitter, seen, utf8Files, notUtf8Files);
            if (second.cancelled()) {
                emitter.flush();
                return WorkspaceSearch.Outcome.cancelled(WorkspaceSearch.Engine.RIPGREP);
            }
            if (second.failure() != null && !second.encodingUnsupported()) {
                emitter.flush();
                return WorkspaceSearch.Outcome.failed(second.failure());
            }
            if (second.encodingUnsupported()) {
                suffix = "ripgrep・Shift_JIS 未対応";
            }
        }
        emitter.flush();
        return WorkspaceSearch.Outcome.ripgrep(suffix);
    }

    private static Pass runSjisPass(
            Path executable,
            WorkspaceSearch.Request request,
            WorkspaceSearch.Handle handle,
            Path root,
            FilePathFilter filter,
            Emitter emitter,
            Set<String> seen,
            Set<Path> utf8Files,
            Set<Path> notUtf8Files
    ) throws IOException {
        int start = sjisEncodingIndex;
        for (int n = 0; n < SJIS_ENCODINGS.size(); n++) {
            int index = (start + n) % SJIS_ENCODINGS.size();
            String encoding = SJIS_ENCODINGS.get(index);
            Pass pass = runPass(
                    executable, request, encoding, handle, root, filter, emitter, seen,
                    utf8Files, notUtf8Files, false);
            if (pass.encodingUnsupported()) {
                continue;
            }
            sjisEncodingIndex = index;
            return pass;
        }
        return Pass.unknownEncoding();
    }

    private static Pass runPass(
            Path executable,
            WorkspaceSearch.Request request,
            String encoding,
            WorkspaceSearch.Handle handle,
            Path root,
            FilePathFilter filter,
            Emitter emitter,
            Set<String> seen,
            Set<Path> utf8Files,
            Set<Path> notUtf8Files,
            boolean acceptRawLines
    ) throws IOException {
        if (handle.isCancelled()) {
            return Pass.stopped();
        }
        Path patternFile = writePatternFile(request.query());
        try {
            List<String> command = RipgrepCommand.build(executable, request, encoding, patternFile);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(root.toFile());
            builder.redirectErrorStream(false);
            Process process = builder.start();
            handle.attach(process);
            StringBuilder errors = new StringBuilder();
            Thread stderr = drainStderr(process, errors);
            boolean cancelled = false;
            try {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (handle.isCancelled()) {
                            cancelled = true;
                            Processes.kill(process);
                            break;
                        }
                        if (line.isEmpty()) {
                            continue;
                        }
                        consume(line, root, filter, emitter, seen, utf8Files, notUtf8Files, acceptRawLines, encoding != null);
                    }
                }
            } catch (IOException ex) {
                if (handle.isCancelled()) {
                    cancelled = true;
                } else {
                    throw ex;
                }
            } finally {
                if (cancelled || handle.isCancelled()) {
                    Processes.kill(process);
                }
                handle.detach(process);
                waitFor(process);
                try {
                    stderr.join(1_000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (cancelled || handle.isCancelled()) {
                return Pass.stopped();
            }
            int code;
            try {
                code = process.exitValue();
            } catch (IllegalThreadStateException ex) {
                Processes.kill(process);
                return Pass.failed("ripgrep が終了しませんでした");
            }
            String stderrText = errors.toString().trim();
            if (encoding != null && isUnknownEncoding(stderrText)) {
                return Pass.unknownEncoding();
            }
            if (code == 0 || code == 1) {
                return Pass.ok();
            }
            if (emitter.emitted() > 0 && code == 2) {
                // Partial results with a per-file IO error. Keep what streamed.
                return Pass.ok();
            }
            return Pass.failed(oneLine(stderrText));
        } finally {
            try {
                Files.deleteIfExists(patternFile);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Writes the search pattern to a temp file for {@code rg -f}.
     * Avoids Windows command-line quoting, which mangles embedded {@code "} in {@code -e}.
     */
    private static Path writePatternFile(String query) throws IOException {
        Path file = Files.createTempFile("jcode-rg-pattern-", ".txt");
        try {
            Files.writeString(file, query, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            throw ex;
        }
    }

    private static void consume(
            String json,
            Path root,
            FilePathFilter filter,
            Emitter emitter,
            Set<String> seen,
            Set<Path> utf8Files,
            Set<Path> notUtf8Files,
            boolean acceptRawLines,
            boolean sjisPass
    ) {
        RgJson.Parsed parsed = RgJson.parseLine(json, root);
        if (parsed.matches().isEmpty()) {
            return;
        }
        Path file = parsed.file();
        if (file == null || !acceptFile(root, file, filter)) {
            return;
        }
        if (parsed.rawLine() && !acceptRawLines) {
            return;
        }
        if (sjisPass && skipSjisPass(file, utf8Files, notUtf8Files)) {
            return;
        }
        for (SearchMatch match : parsed.matches()) {
            String key = file + "\0" + match.lineNumber() + "\0" + match.startColumn() + "\0" + match.endColumn();
            if (!seen.add(key)) {
                continue;
            }
            emitter.add(match);
        }
    }

    /**
     * The Windows-31J pass mis-decodes UTF-8 files. Drop those matches when the
     * file was already seen as UTF-8, or when a strict UTF-8 read succeeds.
     */
    private static boolean skipSjisPass(Path file, Set<Path> utf8Files, Set<Path> notUtf8Files) {
        if (utf8Files.contains(file)) {
            return true;
        }
        if (notUtf8Files.contains(file)) {
            return false;
        }
        if (looksUtf8(file)) {
            utf8Files.add(file);
            return true;
        }
        notUtf8Files.add(file);
        return false;
    }

    private static boolean looksUtf8(Path file) {
        try {
            long size = Files.size(file);
            if (size > SearchService.MAX_FILE_BYTES) {
                return false;
            }
            byte[] bytes = Files.readAllBytes(file);
            return SearchService.isStrictUtf8(bytes);
        } catch (IOException ex) {
            return false;
        }
    }

    static boolean acceptFile(Path root, Path file, FilePathFilter filter) {
        if (isUnderSkippedDir(root, file)) {
            return false;
        }
        if (!filter.accept(root, file)) {
            return false;
        }
        return filter.hasFileIncludes() || SearchService.isLikelyText(file);
    }

    static boolean isUnderSkippedDir(Path root, Path file) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (relative.toString().startsWith("..")) {
            return false;
        }
        int names = relative.getNameCount();
        for (int i = 0; i < names - 1; i++) {
            if (SearchService.skipDirNames().contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String query) {
        for (int i = 0; i < query.length(); i++) {
            if (query.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnknownEncoding(String stderr) {
        if (stderr == null) {
            return false;
        }
        String lower = stderr.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unknown encoding") || lower.contains("unrecognized encoding");
    }

    private static Thread drainStderr(Process process, StringBuilder errors) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (errors.length() < 4_000) {
                        if (errors.length() > 0) {
                            errors.append('\n');
                        }
                        errors.append(line);
                    }
                }
            } catch (IOException ignored) {
                // process killed, or the stream closed
            }
        }, "rg-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void waitFor(Process process) {
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                Processes.kill(process);
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Processes.kill(process);
        }
    }

    static String oneLine(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "ripgrep がエラー終了しました";
        }
        String flat = stderr.trim().replaceAll("\\s+", " ");
        return flat.length() <= 180 ? flat : flat.substring(0, 179) + "…";
    }

    private record Pass(boolean cancelled, boolean encodingUnsupported, String failure) {
        static Pass ok() {
            return new Pass(false, false, null);
        }

        static Pass stopped() {
            return new Pass(true, false, null);
        }

        static Pass unknownEncoding() {
            return new Pass(false, true, null);
        }

        static Pass failed(String message) {
            return new Pass(false, false, message);
        }
    }

    private static final class Emitter {
        private final Consumer<List<SearchMatch>> onBatch;
        private final List<SearchMatch> batch = new ArrayList<>();
        private long lastFlush;
        private int emitted;

        Emitter(Consumer<List<SearchMatch>> onBatch) {
            this.onBatch = onBatch;
            this.lastFlush = System.nanoTime();
        }

        int emitted() {
            return emitted;
        }

        void add(SearchMatch match) {
            batch.add(match);
            emitted++;
            long now = System.nanoTime();
            // Show the first hit immediately, then coalesce so the EDT is not flooded.
            if (emitted == 1 || batch.size() >= 32 || now - lastFlush >= 80_000_000L) {
                flush();
            }
        }

        void flush() {
            if (batch.isEmpty()) {
                return;
            }
            onBatch.accept(List.copyOf(batch));
            batch.clear();
            lastFlush = System.nanoTime();
        }
    }
}
