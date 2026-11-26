package com.jcode.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/** Window / taskbar icons with the "JC" mark. */
public final class Icons {
    private static final List<Image> APP = List.of(
            appMark(16),
            appMark(32),
            appMark(48),
            appMark(64));

    private Icons() {
    }

    public static List<Image> appIcons() {
        return APP;
    }

    private static BufferedImage appMark(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int arc = Math.max(3, size / 5);
        g.setColor(new Color(0x0F766E));
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(new Color(0x14B8A6));
        int inset = Math.max(1, size / 16);
        g.fillRoundRect(inset, inset, size - inset * 2, size - inset * 2, Math.max(2, arc - 1), Math.max(2, arc - 1));
        g.setColor(Color.WHITE);
        int fontSize = Math.max(8, Math.round(size * 0.52f));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        String mark = "JC";
        int x = (size - metrics.stringWidth(mark)) / 2;
        int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(mark, x, y);
        g.dispose();
        return image;
    }
}
