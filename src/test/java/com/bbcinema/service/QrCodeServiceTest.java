package com.bbcinema.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class QrCodeServiceTest {

    private final QrCodeService qr = new QrCodeService();

    @Test
    void modulesForTheMailTableScanBackToTheBookingCode() throws Exception {
        boolean[][] modules = qr.modules("2U38498R");
        assertThat(modules.length).isEqualTo(modules[0].length).isEqualTo(21 + 4); // version 1 + 2-module quiet zone
        assertThat(modules[0][0]).isFalse(); // quiet zone is light
        assertThat(modules[2][2]).isTrue();  // finder pattern corner

        int cell = 6;
        BufferedImage image = new BufferedImage(modules.length * cell, modules.length * cell, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, modules[y / cell][x / cell] ? 0x000000 : 0xFFFFFF);
            }
        }
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        String text = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
        assertThat(text).isEqualTo("2U38498R");
    }
}
