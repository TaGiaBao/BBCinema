package com.bbcinema.security;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final AppUserPrincipal principal = new AppUserPrincipal(TestFixtures.user(7, LocalDate.of(2000, 1, 1)));

    @Test
    void tokenRoundTrip() {
        JwtService jwt = new JwtService(TestFixtures.properties(), TestFixtures.clock());
        String token = jwt.generateToken(principal);
        assertThat(jwt.extractEmail(token)).contains("user7@test.vn");
        assertThat(jwt.getLifetimeSeconds()).isEqualTo(24 * 3600);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = new JwtService(TestFixtures.properties(), TestFixtures.clock());
        String token = jwt.generateToken(principal);
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("A") ? "BBB" : "AAA");
        assertThat(jwt.extractEmail(tampered)).isEmpty();
        assertThat(jwt.extractEmail("not-a-token")).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        String token = new JwtService(TestFixtures.properties(), TestFixtures.clock()).generateToken(principal);
        JwtService later = new JwtService(TestFixtures.properties(), TestFixtures.clockAt(TestFixtures.NOW.plusHours(25)));
        assertThat(later.extractEmail(token)).isEmpty();
    }

    @Test
    void principalExposesRole() {
        User admin = TestFixtures.user(1, null);
        admin.setRole(com.bbcinema.entity.enums.Role.ADMIN);
        AppUserPrincipal p = new AppUserPrincipal(admin);
        assertThat(p.isStaff()).isTrue();
        assertThat(p.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
        assertThat(principal.isStaff()).isFalse();
    }
}
