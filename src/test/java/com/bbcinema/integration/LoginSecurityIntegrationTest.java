package com.bbcinema.integration;

import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.security.LoginAttemptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Failed-login limits on the three ways to sign in with a password, and the login cookie attributes. */
class LoginSecurityIntegrationTest extends IntegrationTestBase {

    private static final String WRONG = "Email hoặc mật khẩu không đúng";

    @Autowired LoginAttemptService loginAttempts;

    @AfterEach
    void forgetAttempts() {
        loginAttempts.clear();
    }

    /** Each test talks from its own address, so address counts never mix between tests. */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private ResultActions customerLogin(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/login").with(csrf()).with(from(ip)).param("email", email).param("password", password));
    }

    private ResultActions internalLogin(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/internal/login").with(csrf()).with(from(ip)).param("email", email).param("password", password));
    }

    private ResultActions apiLogin(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void customerLoginBlocksTheAccountAfterFiveFailures() throws Exception {
        User member = member(20);
        for (int i = 0; i < 4; i++) {
            customerLogin(member.getEmail(), "wrong", "10.1.0.1").andExpect(content().string(containsString(WRONG)));
        }
        customerLogin(member.getEmail().toUpperCase(), "wrong", "10.1.0.1")
                .andExpect(content().string(containsString(LoginAttemptService.BLOCKED_MESSAGE)));
        // Even the right password, from another address, is refused while blocked.
        customerLogin(member.getEmail(), "Secret@123", "10.1.0.2")
                .andExpect(status().isOk())
                .andExpect(cookie().doesNotExist("BB_TOKEN"))
                .andExpect(content().string(containsString(LoginAttemptService.BLOCKED_MESSAGE)));
    }

    @Test
    void unknownEmailsAreBlockedTheSameWay() throws Exception {
        String ghost = "ghost" + unique() + "@test.vn";
        for (int i = 0; i < 4; i++) {
            customerLogin(ghost, "wrong", "10.1.1.1").andExpect(content().string(containsString(WRONG)));
        }
        customerLogin(ghost, "wrong", "10.1.1.1")
                .andExpect(content().string(containsString(LoginAttemptService.BLOCKED_MESSAGE)));
    }

    @Test
    void successfulLoginResetsTheAccountCount() throws Exception {
        User member = member(20);
        for (int i = 0; i < 4; i++) {
            customerLogin(member.getEmail(), "wrong", "10.1.2.1");
        }
        customerLogin(member.getEmail(), "Secret@123", "10.1.2.1").andExpect(cookie().exists("BB_TOKEN"));
        for (int i = 0; i < 4; i++) {
            customerLogin(member.getEmail(), "wrong", "10.1.2.1").andExpect(content().string(containsString(WRONG)));
        }
        customerLogin(member.getEmail(), "Secret@123", "10.1.2.1").andExpect(redirectedUrl("/"));
    }

    @Test
    void internalLoginIsLimitedToo() throws Exception {
        User admin = account(Role.ADMIN);
        for (int i = 0; i < 5; i++) {
            internalLogin(admin.getEmail(), "wrong", "10.1.3.1");
        }
        internalLogin(admin.getEmail(), "Secret@123", "10.1.3.2")
                .andExpect(status().isOk())
                .andExpect(cookie().doesNotExist("BB_TOKEN"))
                .andExpect(content().string(containsString(LoginAttemptService.BLOCKED_MESSAGE)));
    }

    @Test
    void failuresOnOneEntryPointCountOnTheOthers() throws Exception {
        User admin = account(Role.ADMIN);
        for (int i = 0; i < 3; i++) {
            customerLogin(admin.getEmail(), "wrong", "10.1.4.1");
        }
        apiLogin(admin.getEmail(), "wrong", "10.1.4.1").andExpect(status().isUnauthorized());
        internalLogin(admin.getEmail(), "wrong", "10.1.4.1")
                .andExpect(content().string(containsString(LoginAttemptService.BLOCKED_MESSAGE)));
    }

    @Test
    void apiAnswers429WhileBlocked() throws Exception {
        User member = member(20);
        for (int i = 0; i < 4; i++) {
            apiLogin(member.getEmail(), "nope", "10.1.5.1")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(WRONG));
        }
        apiLogin(member.getEmail(), "nope", "10.1.5.1").andExpect(status().isTooManyRequests());
        apiLogin(member.getEmail(), "Secret@123", "10.1.5.2")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.message").value(LoginAttemptService.BLOCKED_MESSAGE))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void apiSuccessResetsTheAccountCount() throws Exception {
        User member = member(20);
        for (int i = 0; i < 4; i++) {
            apiLogin(member.getEmail(), "nope", "10.1.6.1");
        }
        apiLogin(member.getEmail(), "Secret@123", "10.1.6.1").andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            apiLogin(member.getEmail(), "nope", "10.1.6.1").andExpect(status().isUnauthorized());
        }
        apiLogin(member.getEmail(), "Secret@123", "10.1.6.1").andExpect(status().isOk());
    }

    @Test
    void oneAddressIsBlockedAfterTwentyFailures() throws Exception {
        String ip = "10.1.7.1";
        for (int i = 0; i < 20; i++) {
            // A new email every time, so only the address limit can trigger.
            customerLogin("spray" + unique() + "@test.vn", "wrong", ip);
        }
        User member = member(20);
        customerLogin(member.getEmail(), "Secret@123", ip)
                .andExpect(cookie().doesNotExist("BB_TOKEN"))
                .andExpect(content().string(containsString(LoginAttemptService.BLOCKED_MESSAGE)));
        apiLogin(member.getEmail(), "Secret@123", ip).andExpect(status().isTooManyRequests());
        // The same member from another address is fine.
        customerLogin(member.getEmail(), "Secret@123", "10.1.7.2").andExpect(cookie().exists("BB_TOKEN"));
    }

    @Test
    void forwardedForHeaderCannotDodgeTheAddressLimitByDefault() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/login").with(csrf()).with(from("10.1.8.1")).header("X-Forwarded-For", "192.0.2." + i)
                    .param("email", "spray" + unique() + "@test.vn").param("password", "wrong"));
        }
        User member = member(20);
        mvc.perform(post("/login").with(csrf()).with(from("10.1.8.1")).header("X-Forwarded-For", "192.0.2.200")
                        .param("email", member.getEmail()).param("password", "Secret@123"))
                .andExpect(content().string(containsString(LoginAttemptService.BLOCKED_MESSAGE)));
    }

    @Test
    void loginCookieIsNotSecureByDefaultAndLogoutClearsItTheSameWay() throws Exception {
        User member = member(20);
        customerLogin(member.getEmail(), "Secret@123", "10.1.9.1")
                .andExpect(cookie().httpOnly("BB_TOKEN", true))
                .andExpect(cookie().secure("BB_TOKEN", false))
                .andExpect(cookie().sameSite("BB_TOKEN", "Lax"));
        mvc.perform(post("/logout").with(csrf()).cookie(authCookie(member)))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().maxAge("BB_TOKEN", 0))
                .andExpect(cookie().path("BB_TOKEN", "/"))
                .andExpect(cookie().httpOnly("BB_TOKEN", true))
                .andExpect(cookie().secure("BB_TOKEN", false))
                .andExpect(cookie().sameSite("BB_TOKEN", "Lax"));
    }
}
