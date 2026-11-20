package com.jcode.service;

import com.jcode.model.SearchMatch;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses one ripgrep {@code --json} line into {@link SearchMatch}es.
 * <p>
 * Column numbers in the JSON are byte offsets. For a {@code text} field they are
 * UTF-8 bytes of that string (ripgrep transcodes {@code --encoding} output to UTF-8).
 * For a {@code bytes} field they are offsets into the original line bytes, which are
 * Base64. Those lines are not valid UTF-8, so they are decoded with the same
 * UTF-8-then-Windows-31J order as {@link SearchService#decodeBytes(byte[])}.
 */
final class RgJson {

    private RgJson() {
    }

    record Parsed(String type, Path file, boolean rawLine, List<SearchMatch> matches) {
        static Parsed ignore() {
            return new Parsed(null, null, false, List.of());
        }
    }

    static Parsed parseLine(String json, Path root) {
        if (json == null || json.isBlank()) {
            return Parsed.ignore();
        }
        Object value;
        try {
            value = new JsonParser(json).parse();
        } catch (RuntimeException ex) {
            return Parsed.ignore();
        }
        if (!(value instanceof Map<?, ?> map)) {
            return Parsed.ignore();
        }
        String type = asString(map.get("type"));
        if (!"match".equals(type)) {
            return new Parsed(type, null, false, List.of());
        }
        if (!(map.get("data") instanceof Map<?, ?> data)) {
            return Parsed.ignore();
        }
        Path file = pathOf(data.get("path"), root);
        int lineNumber = asInt(data.get("line_number"), -1);
        if (file == null || lineNumber < 1) {
            return Parsed.ignore();
        }
        ByteText line = byteText(data.get("lines"));
        if (line == null || line.containsNul()) {
            return new Parsed(type, file, line != null && line.raw(), List.of());
        }
        if (!(data.get("submatches") instanceof List<?> subs) || subs.isEmpty()) {
            return new Parsed(type, file, line.raw(), List.of());
        }
        String lineText = line.displayText();
        List<SearchMatch> matches = new ArrayList<>();
        for (Object sub : subs) {
            if (!(sub instanceof Map<?, ?> subMap)) {
                continue;
            }
            int startByte = asInt(subMap.get("start"), -1);
            int endByte = asInt(subMap.get("end"), -1);
            if (startByte < 0 || endByte < startByte) {
                continue;
            }
            int startChar = clamp(line.charIndex(startByte), lineText.length());
            int endChar = clamp(line.charIndex(endByte), lineText.length());
            if (endChar < startChar) {
                endChar = startChar;
            }
            matches.add(new SearchMatch(file, lineNumber, startChar + 1, endChar + 1, lineText));
        }
        return new Parsed(type, file, line.raw(), matches);
    }

    private static int clamp(int value, int max) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, max);
    }

    private static Path pathOf(Object node, Path root) {
        ByteText pathText = byteText(node);
        if (pathText == null) {
            return null;
        }
        String text = pathText.raw() ? decodeRawPath(pathText.rawBytes()) : pathText.utf8Text();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(text.trim());
            if (!path.isAbsolute() && root != null) {
                path = root.resolve(path);
            }
            return path.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String decodeRawPath(byte[] raw) {
        SearchService.DecodedText decoded = SearchService.decodeBytes(raw);
        if (decoded != null) {
            return decoded.text();
        }
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static ByteText byteText(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        Object text = map.get("text");
        if (text instanceof String s) {
            return ByteText.utf8(s);
        }
        Object bytes = map.get("bytes");
        if (bytes instanceof String b64 && !b64.isEmpty()) {
            try {
                return ByteText.raw(Base64.getDecoder().decode(b64));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    /**
     * A ripgrep string payload. {@code raw} lines keep the original bytes so column
     * offsets (which count those bytes) survive the charset conversion.
     */
    private static final class ByteText {
        private final String utf8Text;
        private final byte[] rawBytes;
        private final boolean raw;
        private final Charset rawCharset;
        private final String display;

        private ByteText(String utf8Text, byte[] rawBytes, boolean raw, Charset rawCharset, String display) {
            this.utf8Text = utf8Text;
            this.rawBytes = rawBytes;
            this.raw = raw;
            this.rawCharset = rawCharset;
            this.display = display;
        }

        static ByteText utf8(String text) {
            String full = text == null ? "" : text;
            return new ByteText(full, null, false, null, stripEnding(full));
        }

        static ByteText raw(byte[] bytes) {
            byte[] raw = bytes == null ? new byte[0] : bytes;
            SearchService.DecodedText decoded = SearchService.decodeBytes(raw);
            String full = decoded == null ? "" : decoded.text();
            Charset charset = decoded == null ? StandardCharsets.UTF_8 : decoded.charset();
            return new ByteText(null, raw, true, charset, stripEnding(full));
        }

        boolean raw() {
            return raw;
        }

        byte[] rawBytes() {
            return rawBytes;
        }

        String utf8Text() {
            return utf8Text;
        }

        String displayText() {
            return display;
        }

        boolean containsNul() {
            if (rawBytes != null) {
                for (byte b : rawBytes) {
                    if (b == 0) {
                        return true;
                    }
                }
            }
            return utf8Text != null && utf8Text.indexOf('\0') >= 0;
        }

        int charIndex(int byteOffset) {
            if (raw) {
                return SearchService.prefixCharLength(rawBytes, byteOffset, rawCharset);
            }
            return utf8ByteToChar(utf8Text, byteOffset);
        }

        private static String stripEnding(String text) {
            int end = text.length();
            if (end > 0 && text.charAt(end - 1) == '\n') {
                end--;
            }
            if (end > 0 && text.charAt(end - 1) == '\r') {
                end--;
            }
            return end == text.length() ? text : text.substring(0, end);
        }

        /** {@code byteOffset} counts UTF-8 bytes of {@code text}, which is what ripgrep reports. */
        private static int utf8ByteToChar(String text, int byteOffset) {
            if (text == null || text.isEmpty() || byteOffset <= 0) {
                return 0;
            }
            int bytes = 0;
            int i = 0;
            while (i < text.length() && bytes < byteOffset) {
                char c = text.charAt(i);
                int width;
                if (c <= 0x7F) {
                    width = 1;
                } else if (c <= 0x7FF) {
                    width = 2;
                } else if (Character.isHighSurrogate(c)
                        && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1))) {
                    width = 4;
                    if (bytes + width > byteOffset) {
                        break;
                    }
                    bytes += width;
                    i += 2;
                    continue;
                } else {
                    width = 3;
                }
                if (bytes + width > byteOffset) {
                    break;
                }
                bytes += width;
                i++;
            }
            return i;
        }
    }

    /** Minimal JSON parser for the object / array / string / number subset ripgrep emits. */
    static final class JsonParser {
        private final String s;
        private int i;

        JsonParser(String s) {
            this.s = s;
        }

        Object parse() {
            Object value = parseValue();
            skipWs();
            return value;
        }

        private Object parseValue() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalArgumentException("truncated json");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException(word);
            }
            i += word.length();
            return value;
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                map.put(key, parseValue());
                skipWs();
                if (peek('}')) {
                    i++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (peek(']')) {
                    i++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        int cp = Integer.parseInt(s.substring(i, i + 4), 16);
                        i += 4;
                        out.append((char) cp);
                    }
                    default -> throw new IllegalArgumentException("bad escape");
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Number parseNumber() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) {
                throw new IllegalArgumentException("number");
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            boolean fractional = false;
            if (i < s.length() && s.charAt(i) == '.') {
                fractional = true;
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                fractional = true;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            String token = s.substring(start, i);
            if (fractional) {
                return Double.valueOf(token);
            }
            return Long.valueOf(token);
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    return;
                }
                i++;
            }
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("expected " + c);
            }
            i++;
        }
    }
}
