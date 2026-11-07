package com.jcode.ui.editor;

import com.jcode.ui.ThemeManager;
import com.jcode.ui.UiFonts;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SmartHighlightPainter;

import javax.swing.JComponent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodeEditorPane {

    private final Path file;
    private final String virtualTitle;
    private final boolean virtual;
    private final RSyntaxTextArea textArea;
    private final RTextScrollPane scrollPane;
    private boolean dirty;
    private String lastSavedText;
    private final List<Object> matchHighlights = new ArrayList<>();
    private final List<Object> lineHighlights = new ArrayList<>();

    public CodeEditorPane(Path file) throws IOException {
        this(file, null, Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "",
                detectSyntax(file), false);
    }

    public static CodeEditorPane virtual(String title, Path storageKey, String content, String syntaxStyle) {
        return new CodeEditorPane(storageKey, title, content == null ? "" : content, syntaxStyle, true);
    }

    private CodeEditorPane(Path file, String virtualTitle, String content, String syntaxStyle, boolean virtual) {
        this.file = file;
        this.virtualTitle = virtualTitle;
        this.virtual = virtual;
        this.textArea = new RSyntaxTextArea();
        textArea.setSyntaxEditingStyle(syntaxStyle);
        textArea.setCodeFoldingEnabled(!virtual);
        textArea.setAntiAliasingEnabled(true);
        textArea.setTabSize(4);
        textArea.setTabsEmulated(true);
        textArea.setFont(UiFonts.mono(14f));
        // Virtual docs stay editable so results can be selected / copied freely
        textArea.setEditable(true);
        applyEditorTheme();

        textArea.setText(content);
        textArea.setCaretPosition(0);
        this.lastSavedText = content;
        this.dirty = false;

        if (!virtual) {
            textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    markDirty();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    markDirty();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    markDirty();
                }
            });
        }

        this.scrollPane = new RTextScrollPane(textArea);
        scrollPane.setLineNumbersEnabled(true);
        scrollPane.setFoldIndicatorEnabled(!virtual);
    }

    public Path getFile() {
        return file;
    }

    public boolean isVirtual() {
        return virtual;
    }

    public String displayName() {
        return virtual ? virtualTitle : file.getFileName().toString();
    }

    public void replaceContent(String content) {
        clearCustomHighlights();
        textArea.setText(content == null ? "" : content);
        textArea.setCaretPosition(0);
        lastSavedText = textArea.getText();
        dirty = false;
    }

    /** Reload file from disk (used after workspace replace). Skips virtual tabs. */
    public void reloadFromDisk() throws IOException {
        if (virtual) {
            return;
        }
        String content = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        int caret = textArea.getCaretPosition();
        clearCustomHighlights();
        textArea.setText(content);
        textArea.setCaretPosition(Math.min(caret, content.length()));
        lastSavedText = content;
        dirty = false;
    }

    /**
     * Highlights hit lines (those marked with {@code N:}) and the matched query text inside them.
     * Line wash uses RSyntaxTextArea line-highlight layer; matches use SmartHighlightPainter
     * so the two do not paint over each other.
     */
    public void highlightSearchReport(
            String query,
            boolean matchCase,
            boolean wholeWord,
            boolean regex
    ) {
        clearCustomHighlights();
        if (query == null || query.isEmpty()) {
            return;
        }

        String text = textArea.getText();
        Highlighter highlighter = textArea.getHighlighter();
        boolean dark = ThemeManager.isDark();
        Color lineColor = dark ? new Color(36, 58, 88) : new Color(210, 230, 255);
        SmartHighlightPainter matchPainter = new SmartHighlightPainter(
                dark ? new Color(255, 160, 0) : new Color(255, 140, 0)
        );
        matchPainter.setPaintBorder(true);

        Pattern hitLine = Pattern.compile("(?m)^[ \\t]*\\d+:.*$");
        Pattern queryPattern = compileQuery(query, matchCase, wholeWord, regex);
        Matcher lineMatcher = hitLine.matcher(text);
        while (lineMatcher.find()) {
            try {
                int docLine = textArea.getLineOfOffset(lineMatcher.start());
                lineHighlights.add(textArea.addLineHighlight(docLine, lineColor));

                // Match only in the source text after "123:"
                String line = lineMatcher.group();
                int colon = line.indexOf(':');
                int contentOffset = colon >= 0 ? colon + 1 : 0;
                Matcher qm = queryPattern.matcher(line);
                while (qm.find()) {
                    if (qm.start() < contentOffset) {
                        continue;
                    }
                    matchHighlights.add(highlighter.addHighlight(
                            lineMatcher.start() + qm.start(),
                            lineMatcher.start() + qm.end(),
                            matchPainter));
                }
            } catch (BadLocationException ignored) {
                // skip bad ranges
            }
        }
    }

    /**
     * Highlights every match of {@code query} in the document (Find All).
     *
     * @return number of matches highlighted
     */
    public int highlightAllMatches(String query, boolean matchCase, boolean wholeWord, boolean regex) {
        clearCustomHighlights();
        if (query == null || query.isEmpty()) {
            return 0;
        }
        Pattern pattern = compileQuery(query, matchCase, wholeWord, regex);
        String text = textArea.getText();
        Highlighter highlighter = textArea.getHighlighter();
        boolean dark = ThemeManager.isDark();
        SmartHighlightPainter painter = new SmartHighlightPainter(
                dark ? new Color(255, 160, 0) : new Color(255, 140, 0)
        );
        painter.setPaintBorder(true);
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                matchHighlights.add(highlighter.addHighlight(matcher.start(), matcher.end(), painter));
                count++;
            } catch (BadLocationException ignored) {
                // skip
            }
        }
        return count;
    }

    public void clearCustomHighlights() {
        Highlighter highlighter = textArea.getHighlighter();
        for (Object tag : matchHighlights) {
            highlighter.removeHighlight(tag);
        }
        matchHighlights.clear();
        for (Object tag : lineHighlights) {
            textArea.removeLineHighlight(tag);
        }
        lineHighlights.clear();
    }

    private static Pattern compileQuery(String query, boolean matchCase, boolean wholeWord, boolean regex) {
        String patternText = regex ? query : Pattern.quote(query);
        if (wholeWord) {
            patternText = "\\b" + patternText + "\\b";
        }
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(patternText, flags);
    }

    public JComponent getComponent() {
        return scrollPane;
    }

    public RSyntaxTextArea getTextArea() {
        return textArea;
    }

    public boolean isDirty() {
        return dirty;
    }

    public String getText() {
        return textArea.getText();
    }

    public void setText(String text) {
        textArea.setText(text);
        markDirty();
    }

    public void save() throws IOException {
        if (virtual) {
            dirty = false;
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, textArea.getText(), StandardCharsets.UTF_8);
        lastSavedText = textArea.getText();
        dirty = false;
    }

    public void goTo(int line, int column) {
        try {
            int offset = textArea.getLineStartOffset(Math.max(0, line - 1)) + Math.max(0, column - 1);
            textArea.setCaretPosition(Math.min(offset, textArea.getDocument().getLength()));
            textArea.requestFocusInWindow();
        } catch (Exception ignored) {
            // out of range
        }
    }

    public void highlightRange(int line, int startColumn, int endColumn) {
        try {
            int start = textArea.getLineStartOffset(Math.max(0, line - 1)) + Math.max(0, startColumn - 1);
            int end = textArea.getLineStartOffset(Math.max(0, line - 1)) + Math.max(0, endColumn - 1);
            textArea.select(start, end);
            textArea.requestFocusInWindow();
        } catch (Exception ignored) {
            goTo(line, startColumn);
        }
    }

    private void markDirty() {
        dirty = !textArea.getText().equals(lastSavedText);
    }

    public void applyEditorTheme() {
        boolean dark = ThemeManager.isDark();
        String resource = dark
                ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
                : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
        try (InputStream in = CodeEditorPane.class.getResourceAsStream(resource)) {
            if (in != null) {
                Theme.load(in).apply(textArea);
            }
        } catch (IOException ignored) {
            // keep default
        }
        textArea.setFont(UiFonts.mono(14f));
        if (dark) {
            textArea.setBackground(new Color(30, 30, 30));
            textArea.setCurrentLineHighlightColor(new Color(40, 40, 40));
        } else {
            textArea.setBackground(new Color(255, 255, 255));
            textArea.setCurrentLineHighlightColor(new Color(240, 240, 240));
        }
    }

    private static String detectSyntax(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return SyntaxConstants.SYNTAX_STYLE_JAVA;
        if (name.endsWith(".xml") || name.endsWith(".fxml")) return SyntaxConstants.SYNTAX_STYLE_XML;
        if (name.endsWith(".json")) return SyntaxConstants.SYNTAX_STYLE_JSON;
        if (name.endsWith(".js")) return SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        if (name.endsWith(".ts")) return SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
        if (name.endsWith(".html") || name.endsWith(".htm")) return SyntaxConstants.SYNTAX_STYLE_HTML;
        if (name.endsWith(".css")) return SyntaxConstants.SYNTAX_STYLE_CSS;
        if (name.endsWith(".py")) return SyntaxConstants.SYNTAX_STYLE_PYTHON;
        if (name.endsWith(".md")) return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return SyntaxConstants.SYNTAX_STYLE_YAML;
        if (name.endsWith(".properties")) return SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
        if (name.endsWith(".sql")) return SyntaxConstants.SYNTAX_STYLE_SQL;
        if (name.endsWith(".sh")) return SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
        if (name.endsWith(".c") || name.endsWith(".h")) return SyntaxConstants.SYNTAX_STYLE_C;
        if (name.endsWith(".cpp") || name.endsWith(".hpp")) return SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
        if (name.endsWith(".cs")) return SyntaxConstants.SYNTAX_STYLE_CSHARP;
        if (name.endsWith(".go")) return SyntaxConstants.SYNTAX_STYLE_GO;
        if (name.endsWith(".rs")) return SyntaxConstants.SYNTAX_STYLE_RUST;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }
}
