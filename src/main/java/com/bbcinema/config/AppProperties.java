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
        boolean seedData,
        Membership membership) {

    /** Membership settings, or the defaults when the section is missing. */
    public Membership membershipOrDefault() {
        return membership != null ? membership : Membership.DEFAULT;
    }

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

    /**
     * Spending (in đồng, over the last {@code periodDays}) needed for each tier, and the discount
     * each tier gets on its orders.
     */
    public record Membership(
            int periodDays,
            long silverFrom,
            long goldFrom,
            long diamondFrom,
            int silverPercent,
            int goldPercent,
            int diamondPercent) {

        public static final Membership DEFAULT = new Membership(365, 1_000_000, 3_000_000, 6_000_000, 5, 10, 15);
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
