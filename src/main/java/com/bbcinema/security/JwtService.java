package com.bbcinema.security;

import com.bbcinema.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** Issues and verifies JSON Web Tokens signed with HMAC-SHA256. */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration lifetime;
    private final Clock clock;

    public JwtService(AppProperties properties, Clock clock) {
        byte[] secret = Decoders.BASE64.decode(properties.jwt().secret());
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be a Base64 string of at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.lifetime = Duration.ofHours(properties.jwt().expirationHours());
        this.clock = clock;
    }

    public String generateToken(AppUserPrincipal user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key)
                .compact();
    }

    /** Returns the e-mail inside a valid, unexpired token, or empty for any bad token. */
    public Optional<String> extractEmail(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getLifetimeSeconds() {
        return lifetime.toSeconds();
    }
}
