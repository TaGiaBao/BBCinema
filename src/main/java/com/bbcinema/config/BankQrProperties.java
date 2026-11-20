package com.bbcinema.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Map;

/**
 * Bank transfer by VietQR (app.bank-qr in application.yml, values from .env). The QR code carries the cinema's
 * account, the amount and the booking code. When a SePay API token is set, incoming transfers are matched
 * automatically; otherwise staff confirm them, and in a demo a button can play the bank's part.
 */
@ConfigurationProperties(prefix = "app.bank-qr")
public record BankQrProperties(
        String bankCode,
        String bankBin,
        String accountNo,
        String accountName,
        String sepayApiToken,
        String sepayApiUrl,
        long pollIntervalMs,
        boolean demoConfirm) {

    /** NAPAS bank identification numbers of common Vietnamese banks, by their usual short code. */
    static final Map<String, String> BINS = Map.ofEntries(
            Map.entry("VCB", "970436"), Map.entry("TCB", "970407"), Map.entry("MB", "970422"),
            Map.entry("ACB", "970416"), Map.entry("BIDV", "970418"), Map.entry("ICB", "970415"),
            Map.entry("VIETINBANK", "970415"), Map.entry("TPB", "970423"), Map.entry("VPB", "970432"),
            Map.entry("STB", "970403"), Map.entry("VBA", "970405"), Map.entry("AGRIBANK", "970405"),
            Map.entry("HDB", "970437"), Map.entry("VIB", "970441"), Map.entry("SHB", "970443"),
            Map.entry("OCB", "970448"), Map.entry("MSB", "970426"), Map.entry("SEAB", "970440"),
            Map.entry("EIB", "970431"), Map.entry("LPB", "970449"), Map.entry("NAB", "970428"));

    /** The BIN to put in the QR code: BANK_BIN if given, else looked up from BANK_CODE. */
    public String resolvedBin() {
        if (bankBin != null && !bankBin.isBlank()) {
            return bankBin.trim();
        }
        return bankCode == null ? null : BINS.get(bankCode.trim().toUpperCase(Locale.ROOT));
    }

    /** Customers can pay by bank transfer only when the QR code can be drawn. */
    public boolean isConfigured() {
        return resolvedBin() != null && accountNo != null && !accountNo.isBlank();
    }

    /** Transfers are matched automatically (SePay) rather than confirmed by hand. */
    public boolean isAutomatic() {
        return isConfigured() && sepayApiToken != null && !sepayApiToken.isBlank();
    }
}
