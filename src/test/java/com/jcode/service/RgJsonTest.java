package com.jcode.service;

import com.jcode.model.SearchMatch;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RgJsonTest {

    private static final Charset MS932 = Charset.forName("windows-31j");

    @Test
    void utf8TextUsesCharacterColumns() {
        Path file = hostFile("utf8.txt");
        String json = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"" + escapeJson(file.toString())
                + "\"},\"lines\":{\"text\":\"hello こんにちは\\n\"},\"line_number\":1,\"absolute_offset\":0,"
                + "\"submatches\":[{\"match\":{\"text\":\"こんにちは\"},\"start\":6,\"end\":21}]}}";
        List<SearchMatch> matches = RgJson.parseLine(json, file.getParent()).matches();

        assertEquals(1, matches.size());
        SearchMatch match = matches.getFirst();
        assertEquals(file, match.file());
        assertEquals(1, match.lineNumber());
        assertEquals("hello こんにちは", match.lineText());
        assertEquals(7, match.startColumn());
        assertEquals(12, match.endColumn());
    }

    @Test
    void shiftJisBytesAreDecodedAndColumnsFollowCharacters() {
        Path file = hostFile("sjis.txt");
        byte[] line = "hello こんにちは\n".getBytes(MS932);
        SearchMatch ascii = RgJson.parseLine(matchLine(file, line, 0, 5), file.getParent()).matches().getFirst();
        assertEquals(file, ascii.file());
        assertEquals("hello こんにちは", ascii.lineText());
        assertEquals(1, ascii.startColumn());
        assertEquals(6, ascii.endColumn());

        SearchMatch kana = RgJson.parseLine(matchLine(file, line, 6, 16), file.getParent()).matches().getFirst();
        assertEquals(file, kana.file());
        assertEquals("hello こんにちは", kana.lineText());
        assertEquals(7, kana.startColumn());
        assertEquals(12, kana.endColumn());
    }

    @Test
    void crlfAndEscapesAreStrippedFromTheDisplayLine() {
        Path file = hostFile("crlf.txt");
        String json = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"" + escapeJson(file.toString())
                + "\"},\"lines\":{\"text\":\"say \\\"hi\\\"\\r\\n\"},\"line_number\":2,\"absolute_offset\":4,"
                + "\"submatches\":[{\"match\":{\"text\":\"hi\"},\"start\":5,\"end\":7}]}}";
        SearchMatch match = RgJson.parseLine(json, file.getParent()).matches().getFirst();
        assertEquals(file, match.file());
        assertEquals("say \"hi\"", match.lineText());
        assertEquals(2, match.lineNumber());
        assertEquals(6, match.startColumn());
        assertEquals(8, match.endColumn());
    }

    @Test
    void nonMatchAndBrokenJsonProduceNoMatches() {
        Path root = hostFile("ignored.txt").getParent();
        assertTrue(RgJson.parseLine("{\"type\":\"summary\",\"data\":{}}", root).matches().isEmpty());
        assertTrue(RgJson.parseLine("{not json", root).matches().isEmpty());
    }

    /** Absolute path on the host OS. {@code /tmp/foo} is not absolute on Windows. */
    private static Path hostFile(String name) {
        return Path.of(name).toAbsolutePath().normalize();
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String matchLine(Path path, byte[] line, int start, int end) {
        String encoded = Base64.getEncoder().encodeToString(line);
        return "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"" + escapeJson(path.toString()) + "\"},"
                + "\"lines\":{\"bytes\":\"" + encoded + "\"},\"line_number\":1,\"absolute_offset\":0,"
                + "\"submatches\":[{\"match\":{\"text\":\"x\"},\"start\":" + start + ",\"end\":" + end + "}]}}";
    }
}
