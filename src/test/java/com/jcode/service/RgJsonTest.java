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
        String json = """
                {"type":"match","data":{"path":{"text":"/tmp/utf8.txt"},"lines":{"text":"hello こんにちは\\n"},"line_number":1,"absolute_offset":0,"submatches":[{"match":{"text":"こんにちは"},"start":6,"end":21}]}}
                """;
        List<SearchMatch> matches = RgJson.parseLine(json, Path.of("/tmp")).matches();

        assertEquals(1, matches.size());
        SearchMatch match = matches.getFirst();
        assertEquals(Path.of("/tmp/utf8.txt"), match.file());
        assertEquals(1, match.lineNumber());
        assertEquals("hello こんにちは", match.lineText());
        assertEquals(7, match.startColumn());
        assertEquals(12, match.endColumn());
    }

    @Test
    void shiftJisBytesAreDecodedAndColumnsFollowCharacters() {
        byte[] line = "hello こんにちは\n".getBytes(MS932);
        String hello = matchLine("/tmp/sjis.txt", line, 0, 5);
        SearchMatch ascii = RgJson.parseLine(hello, Path.of("/tmp")).matches().getFirst();
        assertEquals("hello こんにちは", ascii.lineText());
        assertEquals(1, ascii.startColumn());
        assertEquals(6, ascii.endColumn());

        String japanese = matchLine("/tmp/sjis.txt", line, 6, 16);
        SearchMatch kana = RgJson.parseLine(japanese, Path.of("/tmp")).matches().getFirst();
        assertEquals("hello こんにちは", kana.lineText());
        assertEquals(7, kana.startColumn());
        assertEquals(12, kana.endColumn());
    }

    @Test
    void crlfAndEscapesAreStrippedFromTheDisplayLine() {
        String json = """
                {"type":"match","data":{"path":{"text":"/tmp/crlf.txt"},"lines":{"text":"say \\"hi\\"\\r\\n"},"line_number":2,"absolute_offset":4,"submatches":[{"match":{"text":"hi"},"start":5,"end":7}]}}
                """;
        SearchMatch match = RgJson.parseLine(json, Path.of("/tmp")).matches().getFirst();
        assertEquals("say \"hi\"", match.lineText());
        assertEquals(2, match.lineNumber());
        assertEquals(6, match.startColumn());
        assertEquals(8, match.endColumn());
    }

    @Test
    void nonMatchAndBrokenJsonProduceNoMatches() {
        assertTrue(RgJson.parseLine("{\"type\":\"summary\",\"data\":{}}", Path.of("/tmp")).matches().isEmpty());
        assertTrue(RgJson.parseLine("{not json", Path.of("/tmp")).matches().isEmpty());
    }

    private static String matchLine(String path, byte[] line, int start, int end) {
        String encoded = Base64.getEncoder().encodeToString(line);
        return "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"" + path + "\"},"
                + "\"lines\":{\"bytes\":\"" + encoded + "\"},\"line_number\":1,\"absolute_offset\":0,"
                + "\"submatches\":[{\"match\":{\"text\":\"x\"},\"start\":" + start + ",\"end\":" + end + "}]}}";
    }
}
