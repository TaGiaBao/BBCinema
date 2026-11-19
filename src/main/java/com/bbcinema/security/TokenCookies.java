package com.bbcinema.security;

import com.bbcinema.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Writes the login cookie (JWT, HttpOnly) after any kind of successful sign-in. */
@Component
@RequiredArgsConstructor
public class TokenCookies {

    private final JwtService jwtService;
    private final AppProperties properties;

    public void signIn(HttpServletResponse response, AppUserPrincipal user) {
        ResponseCookie cookie = ResponseCookie.from(properties.jwt().cookieName(), jwtService.generateToken(user))
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(jwtService.getLifetimeSeconds()))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}
