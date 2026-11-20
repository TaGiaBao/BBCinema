package com.bbcinema.service;

import com.bbcinema.config.BankQrProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VietQrTest {

    @Test
    void crcIsTheCcittFalseVariantRequiredByEmvco() {
        assertThat(VietQr.crc16("123456789")).isEqualTo(0x29B1); // standard check value
    }

    @Test
    void payloadCarriesBankAccountAmountAndMessage() {
        String qr = VietQr.payload("970422", "0123456789", new BigDecimal("120000"), "BBC JF5XSY24");
        assertThat(qr)
                .startsWith("000201" + "010212")                       // format 01, dynamic QR
                .contains("0010A000000727")                             // NAPAS
                .contains("0006970422" + "01100123456789")              // bank BIN + account
                .contains("0208QRIBFTTA")                               // transfer to an account
                .contains("5303704")                                    // VND
                .contains("5406120000")                                 // amount
                .contains("5802VN")
                .contains("62160812BBC JF5XSY24");                      // message
        // the last 4 characters are the CRC of everything before them
        String body = qr.substring(0, qr.length() - 4);
        assertThat(body).endsWith("6304");
        assertThat(qr.substring(qr.length() - 4)).isEqualTo(String.format("%04X", VietQr.crc16(body)));
    }

    @Test
    void messageKeepsOnlyLettersDigitsAndSpaces() {
        assertThat(VietQr.plain("Vé Đỉnh Gió - BBC#12")).isEqualTo("VE DINH GIO  BBC12");
    }

    @Test
    void bankBinComesFromTheShortCodeUnlessGivenDirectly() {
        BankQrProperties mb = new BankQrProperties("mb", "", "0123", "A", "", "", 5000, false);
        assertThat(mb.resolvedBin()).isEqualTo("970422");
        assertThat(mb.isConfigured()).isTrue();
        assertThat(mb.isAutomatic()).isFalse();
        BankQrProperties custom = new BankQrProperties("XYZ", "970999", "0123", "A", "token", "", 5000, false);
        assertThat(custom.resolvedBin()).isEqualTo("970999");
        assertThat(custom.isAutomatic()).isTrue();
        assertThat(new BankQrProperties("XYZ", "", "0123", "A", "", "", 5000, false).isConfigured()).isFalse();
        assertThat(new BankQrProperties("MB", "", "", "A", "", "", 5000, false).isConfigured()).isFalse();
    }
}
