package com.jcode.service;

import com.jcode.model.SearchMatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RipgrepSearchTest {

    private static final Charset MS932 = Charset.forName("windows-31j");

    @TempDir
    Path root;

    private Path rg;

    @BeforeEach
    void setUp() throws Exception {
        rg = RipgrepLocator.resolve();
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/A.java"), "Hello hello\nfoo foobar\n検索\n");
        Files.writeString(root.resolve("src/main/B.txt"), "hello\n");
        Files.writeString(root.resolve("src/utf8.txt"), "hello こんにちは world\n");
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("other/C.java"), "hello\n");
        Files.createDirectories(root.resolve("notes"));
        Files.write(root.resolve("notes/sjis.txt"), "hello こんにちは\n検索\n".getBytes(MS932));
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/Skip.java"), "hello\n");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules/X.java"), "hello\n");
        Files.writeString(root.resolve("Makefile"), "hello: all\n");
        Files.createDirectories(root.resolve("ignored"));
        Files.writeString(root.resolve("ignored/A.java"), "hello\n");
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.writeString(root.resolve(".hidden.txt"), "hello\n");
    }

    @Test
    void literalSearchMatchesJavaEngine() throws Exception {
        compare("hello", false, false, false, null, null, null, null);
        compare("Hello", true, false, false, null, null, null, null);
        compare("foo", false, true, false, null, null, null, null);
        compare("h.llo", false, false, true, null, null, null, null);
        compare("hello", false, false, false, "*.java", null, null, null);
        compare("hello", false, false, false, null, "src", null, null);
        compare("hello", false, false, false, "*.java", "src", null, null);
        compare("hello", false, false, false, null, null, "*.txt", null);
        compare("hello", false, false, false, null, null, null, "notes");
        compare("検索", false, false, false, null, null, null, null);
        compare("こんにちは", false, false, false, null, null, null, null);
    }

    @Test
    void invalidRegexIsReported() {
        Assumptions.assumeTrue(rg != null, "ripgrep is not installed");
        WorkspaceSearch.Outcome outcome = search("[", false, false, true, null, null, null, null).outcome();
        assertNotNull(outcome.error());
        assertFalse(outcome.cancelled());
    }

    @Test
    void commandMapsOptionsOntoRgFlags() {
        WorkspaceSearch.Request request = new WorkspaceSearch.Request(
                root, "Foo", true, true, false, "*.java", "src", "*.min.js", "target");
        List<String> args = RipgrepCommand.build(Path.of("rg"), request, "windows-31j");

        assertTrue(args.contains("--json"));
        assertTrue(args.contains("--no-config"));
        assertTrue(args.contains("--no-ignore"));
        assertTrue(args.contains("--hidden"));
        assertTrue(args.contains("--case-sensitive"));
        assertFalse(args.contains("--ignore-case"));
        assertTrue(args.contains("--fixed-strings"));
        assertTrue(args.contains("--word-regexp"));
        assertEquals("2000000", args.get(args.indexOf("--max-filesize") + 1));
        assertEquals("windows-31j", args.get(args.indexOf("--encoding") + 1));
        assertEquals("Foo", args.get(args.indexOf("-e") + 1));
        assertTrue(args.contains("**/src/**/*.java"));
        assertTrue(args.contains("!**/target/**"));
        assertFalse(args.contains("!*.min.js"));
        assertEquals(root.toAbsolutePath().normalize().toString(), args.getLast());
    }

    @Test
    void javaFallbackRunsWhenBinaryIsMissing() throws Exception {
        Path dir = Files.createTempDirectory("jcode-fallback");
        Files.writeString(dir.resolve("A.java"), "hello\n");
        Collected found = collect(null, dir, "hello", false, false, false, null, null, null, null);
        assertEquals(WorkspaceSearch.Engine.JAVA, found.outcome().engine());
        assertEquals("Java・ripgrep なし", found.outcome().summarySuffix());
        assertNull(found.outcome().error());
        assertEquals(1, found.matches().size());
        assertEquals("hello", found.matches().getFirst().lineText());
    }

    @Test
    void cancelKillsTheProcess() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")),
                "cancelling a shell script needs /bin/sh");
        Path script = Files.createTempFile("fake-rg", ".sh");
        Files.writeString(script, "#!/bin/sh\nexec sleep 30\n");
        Assumptions.assumeTrue(script.toFile().setExecutable(true),
                "could not mark the fake rg script executable");
        WorkspaceSearch.Handle handle = new WorkspaceSearch.Handle();
        AtomicReference<WorkspaceSearch.Outcome> outcome = new AtomicReference<>();
        Thread thread = new Thread(() -> outcome.set(WorkspaceSearch.run(
                script,
                new WorkspaceSearch.Request(root, "hello", true, false, false, null, null, null, null),
                handle,
                ignored -> {
                }
        )), "rg-cancel-test");
        thread.start();
        Thread.sleep(200);
        handle.cancel();
        thread.join(5_000);

        assertFalse(thread.isAlive(), "search thread did not stop after cancel");
        assertNotNull(outcome.get());
        assertTrue(outcome.get().cancelled());
    }

    private void compare(
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs
    ) {
        Assumptions.assumeTrue(rg != null, "ripgrep is not installed");
        Collected found = collect(rg, root, query, matchCase, wholeWord, regex,
                includeFiles, includeDirs, excludeFiles, excludeDirs);
        assertNull(found.outcome().error(), found.outcome().error());
        assertEquals(WorkspaceSearch.Engine.RIPGREP, found.outcome().engine());
        List<SearchMatch> java = javaMatches(query, matchCase, wholeWord, regex,
                includeFiles, includeDirs, excludeFiles, excludeDirs);
        Set<String> expected = keys(java);
        Set<String> actual = keys(found.matches());
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(expected);
        assertEquals(Set.of(), missing, query + " missing from ripgrep");
        assertEquals(Set.of(), extra, query + " extra in ripgrep");
    }

    private List<SearchMatch> javaMatches(
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs
    ) {
        try {
            return SearchService.findInWorkspace(
                    root, query, matchCase, wholeWord, regex,
                    includeFiles, includeDirs, excludeFiles, excludeDirs);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static Collected collect(
            Path executable,
            Path root,
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs
    ) {
        List<SearchMatch> matches = new ArrayList<>();
        WorkspaceSearch.Outcome outcome = WorkspaceSearch.run(
                executable,
                new WorkspaceSearch.Request(
                        root, query, matchCase, wholeWord, regex,
                        includeFiles, includeDirs, excludeFiles, excludeDirs),
                new WorkspaceSearch.Handle(),
                matches::addAll
        );
        return new Collected(outcome, matches);
    }

    private Collected search(
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex,
            String includeFiles,
            String includeDirs,
            String excludeFiles,
            String excludeDirs
    ) {
        return collect(rg, root, query, matchCase, wholeWord, regex,
                includeFiles, includeDirs, excludeFiles, excludeDirs);
    }

    private static Set<String> keys(List<SearchMatch> matches) {
        Set<String> keys = new TreeSet<>();
        for (SearchMatch match : matches) {
            keys.add(match.file().toAbsolutePath().normalize()
                    + "|" + match.lineNumber()
                    + "|" + match.startColumn()
                    + "|" + match.endColumn()
                    + "|" + match.lineText());
        }
        return keys;
    }

    private record Collected(WorkspaceSearch.Outcome outcome, List<SearchMatch> matches) {
    }
}
