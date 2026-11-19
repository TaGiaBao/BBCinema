package com.bbcinema.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All BBCinema settings from the "app" section of application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        String timezone,
        Booking booking,
        Points points,
        Jwt jwt,
        Upload upload,
        Mail mail,
        VnPay vnpay,
        boolean seedData) {

    public record Booking(
            int holdMinutes,
            int cancelBeforeHours,
            int cleaningMinutes,
            int checkInOpensMinutesBefore) {
    }

    public record Points(
            int earnPercent,
            int valuePerPoint,
            int maxRedeemPercent) {
    }

    public record Jwt(
            String secret,
            long expirationHours,
            String cookieName) {
    }

    public record Upload(String dir) {
    }

    public record Mail(boolean enabled, String from) {
    }

    public record VnPay(
            String tmnCode,
            String hashSecret,
            String payUrl,
            String returnPath) {

        public boolean isConfigured() {
            return tmnCode != null && !tmnCode.isBlank() && hashSecret != null && !hashSecret.isBlank();
        }
    }
}
