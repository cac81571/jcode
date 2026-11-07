package com.jcode.ui.search;

import com.jcode.service.SearchService;
import com.jcode.ui.editor.CodeEditorPane;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-editor find/replace bar (Ctrl+F / Ctrl+H style).
 */
public final class FindReplaceBar extends JPanel {

    private final JTextField findField = new JTextField(28);
    private final JTextField replaceField = new JTextField(28);
    private final JCheckBox matchCase = new JCheckBox("大文字小文字を区別");
    private final JCheckBox wholeWord = new JCheckBox("単語単位");
    private final JCheckBox regex = new JCheckBox("正規表現");
    private final JLabel status = new JLabel(" ");
    private final Supplier<CodeEditorPane> activeEditor;

    public FindReplaceBar(Supplier<CodeEditorPane> activeEditor) {
        super(new BorderLayout(8, 4));
        this.activeEditor = activeEditor;
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        findField.putClientProperty("JTextField.placeholderText", "検索文字列");
        replaceField.putClientProperty("JTextField.placeholderText", "置換文字列");

        JLabel findLabel = new JLabel("検索文字");
        JLabel replaceLabel = new JLabel("置換文字");
        equalizeLabelWidth(findLabel, replaceLabel);

        JButton findNext = new JButton("次を検索");
        JButton findPrev = new JButton("前を検索");
        JButton findAll = new JButton("すべて検索");
        JButton replace = new JButton("置換");
        JButton replaceAll = new JButton("すべて置換");
        JButton close = new JButton("×");

        findNext.addActionListener(e -> find(true));
        findPrev.addActionListener(e -> find(false));
        findAll.addActionListener(e -> findAll());
        replace.addActionListener(e -> replaceOne());
        replaceAll.addActionListener(e -> replaceAll());
        close.addActionListener(e -> {
            clearEditorHighlights();
            setVisible(false);
        });
        findField.addActionListener(e -> find(true));

        JPanel findActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        findActions.add(findNext);
        findActions.add(findPrev);
        findActions.add(findAll);
        findActions.add(close);

        JPanel replaceActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        replaceActions.add(replace);
        replaceActions.add(replaceAll);
        equalizePanelWidth(findActions, replaceActions);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(findLabel, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(findField, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(findActions, c);

        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(replaceLabel, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(replaceField, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(replaceActions, c);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        options.add(matchCase);
        options.add(wholeWord);
        options.add(regex);

        JPanel south = new JPanel(new BorderLayout());
        south.add(options, BorderLayout.WEST);
        south.add(status, BorderLayout.EAST);

        add(form, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        setFocusTraversalKeysEnabled(true);
        registerKeyboard(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), () -> {
            clearEditorHighlights();
            setVisible(false);
        });

        setVisible(false);
    }

    public void showFind() {
        setVisible(true);
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    public void showReplace() {
        setVisible(true);
        replaceField.requestFocusInWindow();
        replaceField.selectAll();
    }

    private void find(boolean forward) {
        CodeEditorPane editor = activeEditor.get();
        if (editor == null) {
            status.setText("エディタがありません");
            return;
        }
        String query = findField.getText();
        if (query.isEmpty()) {
            return;
        }

        RSyntaxTextArea area = editor.getTextArea();
        Pattern pattern = compile(query);
        Matcher matcher = pattern.matcher(area.getText());

        int from = area.getCaretPosition();
        boolean found = false;
        if (forward) {
            if (matcher.find(Math.min(from + (area.getSelectedText() != null ? 1 : 0), area.getText().length()))
                    || matcher.reset().find()) {
                select(area, matcher.start(), matcher.end());
                found = true;
            }
        } else {
            int lastStart = -1;
            int lastEnd = -1;
            int beforeStart = -1;
            int beforeEnd = -1;
            int caret = area.getSelectionStart() > 0 ? area.getSelectionStart() : from;
            while (matcher.find()) {
                lastStart = matcher.start();
                lastEnd = matcher.end();
                if (matcher.end() <= caret) {
                    beforeStart = matcher.start();
                    beforeEnd = matcher.end();
                }
            }
            if (beforeStart >= 0) {
                select(area, beforeStart, beforeEnd);
                found = true;
            } else if (lastStart >= 0) {
                select(area, lastStart, lastEnd);
                found = true;
            }
        }

        status.setText(found ? "見つかりました" : "結果なし");
    }

    private void findAll() {
        CodeEditorPane editor = activeEditor.get();
        if (editor == null) {
            status.setText("エディタがありません");
            return;
        }
        String query = findField.getText();
        if (query.isEmpty()) {
            status.setText(" ");
            editor.clearCustomHighlights();
            return;
        }
        int count = editor.highlightAllMatches(
                query,
                matchCase.isSelected(),
                wholeWord.isSelected(),
                regex.isSelected()
        );
        if (count > 0) {
            // Jump to first match
            find(true);
            status.setText(count + " 件");
        } else {
            status.setText("結果なし");
        }
    }

    private void replaceOne() {
        CodeEditorPane editor = activeEditor.get();
        if (editor == null) {
            return;
        }
        RSyntaxTextArea area = editor.getTextArea();
        String selected = area.getSelectedText();
        String query = findField.getText();
        if (selected != null && matches(selected, query)) {
            area.replaceSelection(replaceField.getText());
        }
        find(true);
    }

    private void replaceAll() {
        CodeEditorPane editor = activeEditor.get();
        if (editor == null) {
            return;
        }
        String query = findField.getText();
        if (query.isEmpty()) {
            status.setText("検索文字を入力してください");
            return;
        }
        String original = editor.getText();
        Matcher matcher = compile(query).matcher(original);
        int hits = 0;
        while (matcher.find()) {
            hits++;
        }
        if (hits == 0) {
            status.setText("置換対象なし");
            return;
        }

        String replacement = replaceField.getText();
        String confirm = hits + " 件を\n"
                + "「" + truncate(query, 40) + "」→「" + truncate(replacement, 40) + "」\n"
                + "に置換しますか？";
        int choice = JOptionPane.showConfirmDialog(
                this,
                confirm,
                "すべて置換",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        String replaced = SearchService.replaceInText(
                original,
                query,
                replacement,
                matchCase.isSelected(),
                wholeWord.isSelected(),
                regex.isSelected(),
                true
        );
        if (!original.equals(replaced)) {
            int caret = editor.getTextArea().getCaretPosition();
            editor.setText(replaced);
            editor.getTextArea().setCaretPosition(Math.min(caret, replaced.length()));
            editor.clearCustomHighlights();
            status.setText(hits + " 件を置換しました");
        } else {
            status.setText("置換対象なし");
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private void clearEditorHighlights() {
        CodeEditorPane editor = activeEditor.get();
        if (editor != null) {
            editor.clearCustomHighlights();
        }
    }

    private boolean matches(String selected, String query) {
        return compile(query).matcher(selected).matches();
    }

    private Pattern compile(String query) {
        String patternText = regex.isSelected() ? query : Pattern.quote(query);
        if (wholeWord.isSelected()) {
            patternText = "\\b" + patternText + "\\b";
        }
        int flags = matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(patternText, flags);
    }

    private static void select(RSyntaxTextArea area, int start, int end) {
        area.select(start, end);
        area.requestFocusInWindow();
    }

    private static void equalizeLabelWidth(JLabel... labels) {
        int max = 0;
        for (JLabel label : labels) {
            max = Math.max(max, label.getPreferredSize().width);
        }
        for (JLabel label : labels) {
            Dimension size = new Dimension(max, label.getPreferredSize().height);
            label.setPreferredSize(size);
            label.setMinimumSize(size);
        }
    }

    private static void equalizePanelWidth(JPanel... panels) {
        int max = 0;
        for (JPanel panel : panels) {
            max = Math.max(max, panel.getPreferredSize().width);
        }
        for (JPanel panel : panels) {
            Dimension size = panel.getPreferredSize();
            Dimension equal = new Dimension(max, size.height);
            panel.setPreferredSize(equal);
            panel.setMinimumSize(equal);
        }
    }

    private void registerKeyboard(KeyStroke stroke, Runnable action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, "action");
        getActionMap().put("action", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }
}
