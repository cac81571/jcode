package com.jcode.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RipgrepLocatorTest {

    @TempDir
    Path temp;

    @Test
    void jarDirectoryBeatsWorkingDirectoryConfiguredAndPath() throws Exception {
        Path jarDir = directory("jar");
        Path workDir = directory("work");
        Path pathDir = directory("path");
        Path inJar = file(jarDir.resolve("rg.exe"), "jar");
        file(workDir.resolve("rg.exe"), "work");
        Path configured = file(temp.resolve("configured-rg"), "configured");
        file(pathDir.resolve("rg.exe"), "path");

        Path found = RipgrepLocator.resolve(lookup(jarDir, workDir, configured.toString(), pathDir.toString(), true));

        assertEquals(inJar, found);
    }

    @Test
    void workingDirectoryIsUsedWhenJarDirectoryHasNoBinary() throws Exception {
        Path workDir = directory("work");
        Path inWork = file(workDir.resolve("rg.exe"), "work");
        Path pathDir = directory("path");
        file(pathDir.resolve("rg.exe"), "path");

        Path found = RipgrepLocator.resolve(lookup(directory("empty-jar"), workDir, null, pathDir.toString(), true));

        assertEquals(inWork, found);
    }

    @Test
    void configuredPathBeatsPath() throws Exception {
        Path pathDir = directory("path");
        file(pathDir.resolve("rg.exe"), "path");
        Path configured = file(temp.resolve("configured-rg"), "configured");

        Path found = RipgrepLocator.resolve(lookup(null, null, configured.toString(), pathDir.toString(), true));

        assertEquals(configured, found);
    }

    @Test
    void missingConfiguredPathFallsThroughToPath() throws Exception {
        Path pathDir = directory("path");
        Path inPath = file(pathDir.resolve("rg.exe"), "path");

        Path found = RipgrepLocator.resolve(lookup(
                null, null, temp.resolve("missing-rg").toString(), pathDir.toString(), true));

        assertEquals(inPath, found);
    }

    @Test
    void returnsNullWhenNothingIsRunnable() {
        assertNull(RipgrepLocator.resolve(lookup(null, null, null, temp.resolve("empty-path").toString(), true)));
    }

    @Test
    void windowsAcceptsRgExeWithoutExecutableBit() throws Exception {
        Path jarDir = directory("win");
        Path exe = jarDir.resolve("rg.exe");
        Files.writeString(exe, "windows");

        Path found = RipgrepLocator.resolve(lookup(jarDir, null, null, null, true));

        assertEquals(exe.toAbsolutePath().normalize(), found);
    }

    @Test
    void unixIgnoresNonExecutableFile() throws Exception {
        Assumptions.assumeTrue(posixFilesystem(), "execute-bit checks need a POSIX filesystem");
        Path jarDir = directory("unix");
        Path binary = jarDir.resolve("rg");
        Files.writeString(binary, "not executable");
        binary.toFile().setExecutable(false, false);

        assertNull(RipgrepLocator.resolve(lookup(jarDir, null, null, null, false)));
    }

    @Test
    void configuredPathRoundTrip() {
        String previous = RipgrepLocator.configuredPath();
        try {
            RipgrepLocator.setConfiguredPath("  C:\\tools\\rg.exe  ");
            assertEquals("C:\\tools\\rg.exe", RipgrepLocator.configuredPath());
            RipgrepLocator.setConfiguredPath(" ");
            assertEquals("", RipgrepLocator.configuredPath());
        } finally {
            RipgrepLocator.setConfiguredPath(previous);
        }
    }

    private Path directory(String name) throws Exception {
        return Files.createDirectory(temp.resolve(name));
    }

    private static Path file(Path path, String body) throws Exception {
        Files.writeString(path, body);
        return path.toAbsolutePath().normalize();
    }

    private static boolean posixFilesystem() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static RipgrepLocator.Lookup lookup(
            Path jarDirectory,
            Path workingDirectory,
            String configured,
            String pathEnv,
            boolean windows
    ) {
        List<String> jarNames = windows ? List.of("rg.exe") : List.of("rg");
        List<String> pathNames = windows ? List.of("rg.exe", "rg") : List.of("rg");
        return new RipgrepLocator.Lookup(jarDirectory, workingDirectory, configured, pathEnv, windows, jarNames, pathNames);
    }
}
