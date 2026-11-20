package com.bbcinema.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Builds the text of a VietQR code (the NAPAS profile of the EMVCo QR standard) that any Vietnamese banking app
 * can scan: it opens a transfer to the given account with the amount and the message already filled in.
 */
public final class VietQr {

    private static final String NAPAS_GUID = "A000000727";
    private static final String TRANSFER_TO_ACCOUNT = "QRIBFTTA";

    private VietQr() {
    }

    public static String payload(String bin, String accountNo, BigDecimal amount, String message) {
        String beneficiary = field("00", bin) + field("01", accountNo);
        String merchant = field("00", NAPAS_GUID) + field("01", beneficiary) + field("02", TRANSFER_TO_ACCOUNT);
        String body = field("00", "01")                       // payload format
                + field("01", "12")                           // dynamic QR: used for one payment
                + field("38", merchant)
                + field("53", "704")                          // currency: VND
                + field("54", amount.setScale(0, java.math.RoundingMode.DOWN).toPlainString())
                + field("58", "VN")
                + field("62", field("08", plain(message)));   // purpose of the transfer
        String withCrcTag = body + "6304";
        return withCrcTag + String.format("%04X", crc16(withCrcTag));
    }

    /** Banks only keep letters, digits and spaces in the transfer message. */
    static String plain(String text) {
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return noAccents.replaceAll("[^A-Za-z0-9 ]", "").toUpperCase(Locale.ROOT);
    }

    private static String field(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    /** CRC-16/CCITT-FALSE (polynomial 0x1021, start 0xFFFF), as the EMVCo standard requires. */
    static int crc16(String text) {
        int crc = 0xFFFF;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return crc & 0xFFFF;
    }
}
