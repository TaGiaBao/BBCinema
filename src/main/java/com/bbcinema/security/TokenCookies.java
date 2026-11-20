package com.bbcinema.security;

import com.bbcinema.config.AppProperties;
import com.bbcinema.config.AppSecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Writes the login cookie (JWT, HttpOnly) after any kind of successful sign-in, and clears it on logout.
 * Both use the same attributes, so the browser really replaces the cookie. The Secure flag follows
 * app.security.cookie-secure (on in production, off for http://localhost).
 */
@Component
@RequiredArgsConstructor
public class TokenCookies {

    private final JwtService jwtService;
    private final AppProperties properties;
    private final AppSecurityProperties securityProperties;

    public void signIn(HttpServletResponse response, AppUserPrincipal user) {
        write(response, jwtService.generateToken(user), Duration.ofSeconds(jwtService.getLifetimeSeconds()));
    }

    /** Tells the browser to drop the login cookie. */
    public void clear(HttpServletResponse response) {
        write(response, "", Duration.ZERO);
    }

    private void write(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(properties.jwt().cookieName(), value)
                .httpOnly(true)
                .secure(securityProperties.cookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
