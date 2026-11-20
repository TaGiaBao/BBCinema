package com.bbcinema.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Login and cookie security settings (app.security in application.yml).
 *
 * @param cookieSecure      send the login cookie only over HTTPS; on in production, off on http://localhost
 * @param trustForwardedFor take the client address from X-Forwarded-For. Only turn this on behind a reverse proxy
 *                          that sets the header, otherwise anyone can fake their address
 * @param login             limits on failed logins
 */
@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        @DefaultValue("false") boolean cookieSecure,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue Login login) {

    /**
     * @param maxFailuresPerAccount failed logins in a row for one email before it is blocked
     * @param maxFailuresPerIp      failed logins from one client address within {@code window} before it is blocked
     * @param window                failures older than this are forgotten
     * @param lockDuration          how long a blocked email or address stays blocked
     * @param maxTrackedKeys        upper bound on the emails and addresses kept in memory
     */
    public record Login(
            @DefaultValue("5") int maxFailuresPerAccount,
            @DefaultValue("20") int maxFailuresPerIp,
            @DefaultValue("15m") Duration window,
            @DefaultValue("15m") Duration lockDuration,
            @DefaultValue("10000") int maxTrackedKeys) {
    }
}
