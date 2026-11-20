package com.bbcinema.security;

import com.bbcinema.config.AppProperties;
import com.bbcinema.config.AppSecurityProperties;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenCookiesTest {

    private static AppSecurityProperties security(boolean cookieSecure, boolean trustForwardedFor) {
        return new AppSecurityProperties(cookieSecure, trustForwardedFor,
                new AppSecurityProperties.Login(5, 20, Duration.ofMinutes(15), Duration.ofMinutes(15), 10_000));
    }

    private static TokenCookies cookies(boolean secure) {
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateToken(any())).thenReturn("token-value");
        when(jwt.getLifetimeSeconds()).thenReturn(3600L);
        AppProperties app = new AppProperties(null, "Asia/Ho_Chi_Minh", null, null,
                new AppProperties.Jwt("secret", 24, "BB_TOKEN"), null, null, null, false, null);
        return new TokenCookies(jwt, app, security(secure, false));
    }

    private static AppUserPrincipal user() {
        User u = new User();
        u.setId(1L);
        u.setEmail("a@test.vn");
        u.setPasswordHash("x");
        u.setRole(Role.USER);
        u.setEnabled(true);
        return new AppUserPrincipal(u);
    }

    @Test
    void loginAndLogoutCookiesAreSecureWhenTheSettingIsOn() {
        MockHttpServletResponse login = new MockHttpServletResponse();
        cookies(true).signIn(login, user());
        assertThat(login.getHeader("Set-Cookie"))
                .startsWith("BB_TOKEN=token-value").contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=3600");

        MockHttpServletResponse logout = new MockHttpServletResponse();
        cookies(true).clear(logout);
        assertThat(logout.getHeader("Set-Cookie"))
                .startsWith("BB_TOKEN=;").contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=0");
    }

    @Test
    void cookiesAreNotSecureWhenTheSettingIsOff() {
        MockHttpServletResponse login = new MockHttpServletResponse();
        cookies(false).signIn(login, user());
        assertThat(login.getHeader("Set-Cookie")).contains("HttpOnly").doesNotContain("Secure");
        MockHttpServletResponse logout = new MockHttpServletResponse();
        cookies(false).clear(logout);
        assertThat(logout.getHeader("Set-Cookie")).contains("Max-Age=0").doesNotContain("Secure");
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
        assertThat(new ClientIp(security(false, false)).of(request)).isEqualTo("10.0.0.1");
        // Behind a trusted proxy: the last address is the one the proxy added; earlier ones may be faked.
        assertThat(new ClientIp(security(false, true)).of(request)).isEqualTo("203.0.113.7");
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("10.0.0.2");
        assertThat(new ClientIp(security(false, true)).of(direct)).isEqualTo("10.0.0.2");
    }
}
