package com.bbcinema.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** Draws the QR code printed on tickets; it encodes the booking code that staff scan at the door. */
@Service
public class QrCodeService {

    /**
     * The QR code as rows of dark (true) and light modules, with a 2-module quiet zone. E-mails draw it as an HTML
     * table, because mail apps (Gmail on phones especially) do not reliably show embedded pictures.
     */
    public boolean[][] modules(String text) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 2));
            boolean[][] rows = new boolean[matrix.getHeight()][matrix.getWidth()];
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    rows[y][x] = matrix.get(x, y);
                }
            }
            return rows;
        } catch (WriterException e) {
            throw new IllegalStateException("Cannot create QR code", e);
        }
    }

    public byte[] png(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Cannot create QR code", e);
        }
    }
}
