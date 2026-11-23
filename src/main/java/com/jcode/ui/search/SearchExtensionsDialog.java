package com.jcode.ui.search;

import com.jcode.service.SearchTextTypes;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.List;

/** Edits the extensions used when Include(file) is empty. */
public final class SearchExtensionsDialog {

    private SearchExtensionsDialog() {
    }

    /** @return true when a list was saved */
    public static boolean show(Component parent) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = create(owner);
        dialog.setTitle("検索の対象拡張子");

        JTextArea area = new JTextArea(SearchTextTypes.format(SearchTextTypes.extensions()), 12, 52);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new JTextField().getFont());

        JLabel hint = new JLabel("<html>Include(file) が空のときに検索する拡張子です。"
                + "カンマ、空白、改行で区切ります。<br>"
                + "*.xhtml や .xhtml は xhtml として保存します。"
                + "Include(file) を指定した検索には、この一覧を使いません。</html>");

        JButton save = new JButton("保存");
        JButton reset = new JButton("初期値に戻す");
        JButton cancel = new JButton("キャンセル");
        reset.setToolTipText("表示を初期値に戻します。保存するまで確定しません");
        boolean[] saved = {false};

        save.addActionListener(e -> {
            try {
                List<String> rejected = SearchTextTypes.rejected(area.getText());
                if (!rejected.isEmpty()) {
                    JOptionPane.showMessageDialog(dialog,
                            "次の指定は拡張子として保存できません:\n" + String.join(", ", rejected),
                            "検索の対象拡張子",
                            JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (SearchTextTypes.isEmpty(area.getText())) {
                    int choice = JOptionPane.showConfirmDialog(dialog,
                            "拡張子が空です。Include(file) を空にすると、Dockerfile などの決まった名前以外は検索されません。\n保存しますか？",
                            "検索の対象拡張子",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    if (choice != JOptionPane.OK_OPTION) {
                        return;
                    }
                }
                SearchTextTypes.save(area.getText());
                saved[0] = true;
                dialog.dispose();
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(dialog,
                        ex.getMessage() == null ? "保存できませんでした。" : ex.getMessage(),
                        "検索の対象拡張子",
                        JOptionPane.WARNING_MESSAGE);
            }
        });
        reset.addActionListener(e -> area.setText(SearchTextTypes.format(SearchTextTypes.defaults())));
        cancel.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(reset);
        buttons.add(save);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(hint, BorderLayout.NORTH);
        content.add(new JScrollPane(area), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(save);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return saved[0];
    }

    private static JDialog create(Window owner) {
        if (owner instanceof Frame frame) {
            return new JDialog(frame, true);
        }
        if (owner instanceof Dialog parent) {
            return new JDialog(parent, true);
        }
        return new JDialog((Frame) null, true);
    }
}
