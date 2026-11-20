package com.jcode.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
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
        Path inJar = executable(jarDir.resolve("rg"), "jar");
        executable(workDir.resolve("rg"), "work");
        Path configured = executable(temp.resolve("configured-rg"), "configured");
        executable(pathDir.resolve("rg"), "path");

        Path found = RipgrepLocator.resolve(lookup(jarDir, workDir, configured.toString(), pathDir.toString(), false));

        assertEquals(inJar, found);
    }

    @Test
    void workingDirectoryIsUsedWhenJarDirectoryHasNoBinary() throws Exception {
        Path workDir = directory("work");
        Path inWork = executable(workDir.resolve("rg"), "work");
        Path pathDir = directory("path");
        executable(pathDir.resolve("rg"), "path");

        Path found = RipgrepLocator.resolve(lookup(directory("empty-jar"), workDir, null, pathDir.toString(), false));

        assertEquals(inWork, found);
    }

    @Test
    void configuredPathBeatsPath() throws Exception {
        Path pathDir = directory("path");
        executable(pathDir.resolve("rg"), "path");
        Path configured = executable(temp.resolve("configured-rg"), "configured");

        Path found = RipgrepLocator.resolve(lookup(null, null, configured.toString(), pathDir.toString(), false));

        assertEquals(configured, found);
    }

    @Test
    void missingConfiguredPathFallsThroughToPath() throws Exception {
        Path pathDir = directory("path");
        Path inPath = executable(pathDir.resolve("rg"), "path");

        Path found = RipgrepLocator.resolve(lookup(
                null, null, temp.resolve("missing-rg").toString(), pathDir.toString(), false));

        assertEquals(inPath, found);
    }

    @Test
    void returnsNullWhenNothingIsRunnable() {
        assertNull(RipgrepLocator.resolve(lookup(null, null, null, temp.resolve("empty-path").toString(), false)));
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
        Path jarDir = directory("unix");
        Files.writeString(jarDir.resolve("rg"), "not executable");

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

    private static Path executable(Path path, String body) throws Exception {
        Files.writeString(path, body);
        Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return path.toAbsolutePath().normalize();
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
