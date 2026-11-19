package com.jcode.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight Java outline: types, fields, and methods.
 * Not a full parser — good enough for typical source layouts.
 */
public final class JavaOutlineParser {

    public enum Kind {
        TYPE, FIELD, METHOD, CONSTRUCTOR
    }

    public record Symbol(Kind kind, String label, int line, List<Symbol> children) {
        public Symbol {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?:^|\\s)(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
                    + "(?:<[^;]*>\\s+)?"
                    + "([\\w.\\[\\]]+(?:\\s*<[^;]*>)?(?:\\s*[\\[\\]])*)\\s+"
                    + "(\\w+)\\s*\\((.*)\\)\\s*(?:throws\\s+.+)?$");

    private JavaOutlineParser() {
    }

    public static List<Symbol> parse(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        Scanner scanner = new Scanner(source);
        scanner.scan();
        return scanner.roots;
    }

    private static final class TypeFrame {
        final String name;
        final boolean isEnum;
        final int bodyDepth;
        final List<Symbol> children = new ArrayList<>();
        final int line;
        TypeFrame parent;

        TypeFrame(String name, boolean isEnum, int bodyDepth, int line) {
            this.name = name;
            this.isEnum = isEnum;
            this.bodyDepth = bodyDepth;
            this.line = line;
        }

        Symbol toSymbol() {
            return new Symbol(Kind.TYPE, name, line, children);
        }
    }

    private static final class Scanner {
        private final String src;
        private final List<Symbol> roots = new ArrayList<>();
        private final List<TypeFrame> stack = new ArrayList<>();
        private int line = 1;
        private int depth;
        private final StringBuilder buf = new StringBuilder();
        /** Source line of each character stored in {@link #buf}. */
        private final List<Integer> bufLines = new ArrayList<>();

        Scanner(String src) {
            this.src = src;
        }

        void scan() {
            boolean lineComment = false;
            boolean blockComment = false;
            boolean string = false;
            boolean textBlock = false;
            boolean chr = false;
            for (int i = 0; i < src.length(); i++) {
                char c = src.charAt(i);
                char n = i + 1 < src.length() ? src.charAt(i + 1) : 0;
                if (c == '\n') {
                    line++;
                    if (lineComment) {
                        lineComment = false;
                    }
                }
                if (lineComment) {
                    continue;
                }
                if (blockComment) {
                    if (c == '*' && n == '/') {
                        blockComment = false;
                        i++;
                    }
                    continue;
                }
                if (textBlock) {
                    if (c == '"' && n == '"' && i + 2 < src.length() && src.charAt(i + 2) == '"') {
                        textBlock = false;
                        i += 2;
                    }
                    continue;
                }
                if (string) {
                    if (c == '\\' && n != 0) {
                        i++;
                        continue;
                    }
                    if (c == '"') {
                        string = false;
                    }
                    continue;
                }
                if (chr) {
                    if (c == '\\' && n != 0) {
                        i++;
                        continue;
                    }
                    if (c == '\'') {
                        chr = false;
                    }
                    continue;
                }
                if (c == '/' && n == '/') {
                    lineComment = true;
                    i++;
                    continue;
                }
                if (c == '/' && n == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
                if (c == '"' && n == '"' && i + 2 < src.length() && src.charAt(i + 2) == '"') {
                    textBlock = true;
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    string = true;
                    continue;
                }
                if (c == '\'') {
                    chr = true;
                    continue;
                }
                if (c == '{') {
                    onOpenBrace();
                    depth++;
                    clearBuf();
                    continue;
                }
                if (c == '}') {
                    depth = Math.max(0, depth - 1);
                    popTypesDeeperThan(depth);
                    clearBuf();
                    continue;
                }
                if (c == ';' && currentType() != null && currentType().bodyDepth == depth) {
                    addFieldLike(false);
                    clearBuf();
                    continue;
                }
                if (c == ',' && currentType() != null && currentType().isEnum && currentType().bodyDepth == depth
                        && buf.indexOf("(") < 0) {
                    addFieldLike(true);
                    clearBuf();
                    continue;
                }
                if (depth <= typeBodyDepth()) {
                    buf.append(c);
                    bufLines.add(line);
                }
            }
            popTypesDeeperThan(-1);
        }

        private int typeBodyDepth() {
            TypeFrame type = currentType();
            return type == null ? 0 : type.bodyDepth;
        }

        private void onOpenBrace() {
            String header = normalize(buf.toString());
            if (header.isEmpty()) {
                return;
            }
            Matcher type = TYPE_DECL.matcher(buf);
            if (type.find()) {
                String name = type.group(1);
                boolean isEnum = header.matches("(?s).*\\benum\\s+" + Pattern.quote(name) + "\\b.*");
                TypeFrame frame = new TypeFrame(name, isEnum, depth + 1, lineAt(type.start(1)));
                frame.parent = currentType();
                stack.add(frame);
                return;
            }
            TypeFrame owner = currentType();
            if (owner != null && owner.bodyDepth == depth) {
                addMethodLike(header, lineOfMethodName(), owner);
            }
        }

        /** Line of the identifier immediately before the parameter list. */
        private int lineOfMethodName() {
            int paren = buf.lastIndexOf("(");
            if (paren < 0) {
                return lineAt(0);
            }
            int i = paren - 1;
            while (i >= 0 && Character.isWhitespace(buf.charAt(i))) {
                i--;
            }
            while (i >= 0 && Character.isJavaIdentifierPart(buf.charAt(i))) {
                i--;
            }
            return lineAt(i + 1);
        }

        /** Line of the last standalone occurrence of {@code name} in the current buffer. */
        private int lineOfToken(String name) {
            int from = 0;
            int found = -1;
            while (from < buf.length()) {
                int i = buf.indexOf(name, from);
                if (i < 0) {
                    break;
                }
                boolean leftOk = i == 0 || !Character.isJavaIdentifierPart(buf.charAt(i - 1));
                int after = i + name.length();
                boolean rightOk = after >= buf.length() || !Character.isJavaIdentifierPart(buf.charAt(after));
                if (leftOk && rightOk) {
                    found = i;
                }
                from = i + name.length();
            }
            return found < 0 ? lineAt(0) : lineAt(found);
        }

        private int lineAt(int index) {
            if (bufLines.isEmpty()) {
                return Math.max(1, line);
            }
            if (index < 0) {
                return bufLines.getFirst();
            }
            if (index >= bufLines.size()) {
                return bufLines.getLast();
            }
            return bufLines.get(index);
        }

        private void popTypesDeeperThan(int newDepth) {
            while (!stack.isEmpty() && stack.getLast().bodyDepth > newDepth) {
                TypeFrame frame = stack.removeLast();
                Symbol symbol = frame.toSymbol();
                if (frame.parent == null) {
                    roots.add(symbol);
                } else {
                    frame.parent.children.add(symbol);
                }
            }
        }

        private void addMethodLike(String header, int atLine, TypeFrame owner) {
            String cleaned = stripAnnotations(header);
            if (cleaned.startsWith("static") && !cleaned.contains("(")) {
                return;
            }
            int paren = cleaned.indexOf('(');
            if (paren < 0) {
                return;
            }
            String before = cleaned.substring(0, paren).trim();
            String params = cleaned.substring(paren + 1);
            int close = params.lastIndexOf(')');
            if (close >= 0) {
                params = params.substring(0, close);
            }
            String[] parts = splitName(before);
            if (parts == null) {
                return;
            }
            String name = parts[1];
            boolean ctor = name.equals(owner.name);
            String label = name + "(" + compactParams(params) + ")";
            if (!ctor && !parts[0].isBlank()) {
                label = label + " : " + shortType(parts[0]);
            }
            owner.children.add(new Symbol(ctor ? Kind.CONSTRUCTOR : Kind.METHOD, label, atLine, List.of()));
        }

        private void addFieldLike(boolean enumConst) {
            TypeFrame owner = currentType();
            if (owner == null) {
                return;
            }
            String cleaned = stripAnnotations(normalize(buf.toString()));
            if (cleaned.isEmpty() || cleaned.startsWith("import ") || cleaned.startsWith("package ")) {
                return;
            }
            if (enumConst || (owner.isEnum && !cleaned.contains("(") && cleaned.matches("[A-Z_][A-Z0-9_]*"))) {
                owner.children.add(new Symbol(Kind.FIELD, cleaned + " : enum", lineOfToken(cleaned), List.of()));
                return;
            }
            String noInit = cleaned.split("=", 2)[0].trim();
            String[] parts = splitName(noInit);
            if (parts == null) {
                return;
            }
            String type = shortType(parts[0]);
            for (String name : parts[1].split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int bracket = trimmed.indexOf('[');
                String shown = bracket >= 0 ? trimmed.substring(0, bracket) : trimmed;
                owner.children.add(new Symbol(Kind.FIELD, shown + " : " + type, lineOfToken(shown), List.of()));
            }
        }

        private TypeFrame currentType() {
            return stack.isEmpty() ? null : stack.getLast();
        }

        private void clearBuf() {
            buf.setLength(0);
            bufLines.clear();
        }
    }

    private static String normalize(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String stripAnnotations(String text) {
        String s = text;
        String prev;
        do {
            prev = s;
            s = s.replaceAll("@\\w+(?:\\s*\\([^)]*\\))?", "").trim();
            s = s.replaceAll("\\s+", " ").trim();
        } while (!s.equals(prev));
        return s;
    }

    /** @return [type, name] or null */
    private static String[] splitName(String beforeParen) {
        String s = beforeParen.trim();
        if (s.isEmpty()) {
            return null;
        }
        int nameStart = s.length();
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                nameStart = i + 1;
                break;
            }
            if (i == 0) {
                nameStart = 0;
            }
        }
        String name = s.substring(nameStart).trim();
        String type = nameStart == 0 ? "" : s.substring(0, nameStart).trim();
        type = type.replaceAll(
                "^(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp|transient|volatile)\\s+)+",
                "");
        if (!name.matches("[A-Za-z_][\\w]*(\\[\\])*")) {
            return null;
        }
        return new String[]{type, name.replace("[]", "")};
    }

    private static String shortType(String type) {
        String t = type.trim();
        if (t.isEmpty()) {
            return "";
        }
        int space = t.lastIndexOf(' ');
        return space >= 0 ? t.substring(space + 1) : t;
    }

    private static String compactParams(String params) {
        String p = params.trim();
        if (p.isEmpty()) {
            return "";
        }
        String[] bits = p.split(",");
        StringBuilder sb = new StringBuilder();
        for (String bit : bits) {
            String part = bit.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            String[] name = splitName(part.replace("...", "[]"));
            sb.append(name == null ? part : shortType(name[0]));
        }
        return sb.toString();
    }
}
