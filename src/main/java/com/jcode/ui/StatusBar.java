package com.jcode.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

public final class StatusBar extends JPanel {

    private final JLabel left = new JLabel("Ready");
    private final JLabel right = new JLabel(" ");

    public StatusBar() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        JPanel rightPane = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        rightPane.setOpaque(false);
        rightPane.add(right);
        add(left, BorderLayout.WEST);
        add(rightPane, BorderLayout.EAST);
    }

    public void setLeft(String text) {
        left.setText(text == null ? " " : text);
    }

    public void setRight(String text) {
        right.setText(text == null ? " " : text);
    }
}
