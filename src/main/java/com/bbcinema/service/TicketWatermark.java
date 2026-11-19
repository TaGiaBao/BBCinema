package com.bbcinema.service;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Draws the faded BBCinema logo used as the background of the e-mailed ticket.
 * Mail clients ignore CSS opacity, so the logo colours are already mixed with white
 * (about 8% strength) and the image is opaque.
 */
@Component
public class TicketWatermark {

    static final int SIZE = 360;
    /** Share of the real colour kept after mixing with white. */
    static final double STRENGTH = 0.08;

    private static final Color AMBER = new Color(0xFF, 0xB4, 0x00);
    private static final Color NAVY = new Color(0x1B, 0x1F, 0x3B);

    private final byte[] png = render();

    public byte[] png() {
        return png.clone();
    }

    static Color faded(Color c) {
        return new Color(mix(c.getRed()), mix(c.getGreen()), mix(c.getBlue()));
    }

    private static int mix(int channel) {
        return (int) Math.round(255 * (1 - STRENGTH) + channel * STRENGTH);
    }

    private static byte[] render() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SIZE, SIZE);

            int pad = SIZE / 12;
            g.setColor(faded(AMBER));
            g.fillRoundRect(pad, pad, SIZE - 2 * pad, SIZE - 2 * pad, SIZE / 4, SIZE / 4);

            g.setColor(faded(NAVY));
            drawCentered(g, "BB", new Font(Font.SANS_SERIF, Font.BOLD, SIZE * 2 / 5), SIZE * 59 / 100);
            drawCentered(g, "CINEMA", new Font(Font.SANS_SERIF, Font.BOLD, SIZE / 8), SIZE * 79 / 100);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void drawCentered(Graphics2D g, String text, Font font, int baseline) {
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (SIZE - metrics.stringWidth(text)) / 2, baseline);
    }
}
