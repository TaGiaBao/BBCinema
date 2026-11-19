package com.bbcinema.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TicketWatermarkTest {

    @Test
    void producesSquarePng() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(new TicketWatermark().png()));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(TicketWatermark.SIZE);
        assertThat(image.getHeight()).isEqualTo(TicketWatermark.SIZE);
    }

    @Test
    void logoIsFaintEnoughToReadTextOnTop() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(new TicketWatermark().png()));
        int darkest = 255;
        boolean hasLogo = false;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                Color c = new Color(image.getRGB(x, y));
                darkest = Math.min(darkest, Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue())));
                hasLogo |= c.getRGB() != Color.WHITE.getRGB();
            }
        }
        assertThat(hasLogo).isTrue();
        // At 8% strength even the navy text of the logo stays above 230 on every channel.
        assertThat(darkest).isGreaterThanOrEqualTo(230);
    }

    @Test
    void fadedMixesColourWithWhite() {
        assertThat(TicketWatermark.faded(Color.BLACK)).isEqualTo(new Color(235, 235, 235));
        assertThat(TicketWatermark.faded(Color.WHITE)).isEqualTo(Color.WHITE);
    }
}
