package com.bbcinema.integration;

import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void publicPagesAreOpen() throws Exception {
        showtimeTomorrow();
        for (String path : new String[]{"/", "/movies", "/schedule", "/prices", "/news", "/cinemas", "/login", "/register"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void memberPagesRedirectToLoginWithReturnAddress() throws Exception {
        mvc.perform(get("/member/bookings"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=%2Fmember%2Fbookings"));
    }

    @Test
    void apiAnswersUnauthorizedWithJson() throws Exception {
        mvc.perform(get("/api/bookings/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void roleRulesAreEnforced() throws Exception {
        User member = member(20);
        User staff = account(Role.STAFF);
        User admin = account(Role.ADMIN);
        mvc.perform(get("/admin").cookie(authCookie(member))).andExpect(status().isForbidden());
        mvc.perform(get("/staff").cookie(authCookie(member))).andExpect(status().isForbidden());
        mvc.perform(get("/staff").cookie(authCookie(staff))).andExpect(status().isOk());
        mvc.perform(get("/admin").cookie(authCookie(staff))).andExpect(status().isForbidden());
        mvc.perform(get("/admin").cookie(authCookie(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/reports/revenue?from=2026-01-01&to=2026-01-02").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void lockedAccountIsLoggedOut() throws Exception {
        User member = member(20);
        member.setEnabled(false);
        userRepository.save(member);
        mvc.perform(get("/member").cookie(authCookie(member))).andExpect(status().is3xxRedirection());
    }

    @Test
    void formLoginSetsHttpOnlyCookie() throws Exception {
        User member = member(20);
        mvc.perform(post("/login").with(csrf()).param("email", member.getEmail()).param("password", "Secret@123")
                        .param("redirect", "/member"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/member"))
                .andExpect(cookie().httpOnly("BB_TOKEN", true));
    }

    @Test
    void wrongPasswordShowsMessage() throws Exception {
        User member = member(20);
        mvc.perform(post("/login").with(csrf()).param("email", member.getEmail()).param("password", "wrong"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Email hoặc mật khẩu không đúng")));
    }

    @Test
    void loginRedirectCannotLeaveTheSite() throws Exception {
        User member = member(20);
        mvc.perform(post("/login").with(csrf()).param("email", member.getEmail()).param("password", "Secret@123")
                        .param("redirect", "https://evil.example"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void formPostWithoutCsrfTokenIsRejected() throws Exception {
        User member = member(20);
        mvc.perform(post("/member/profile").cookie(authCookie(member)).param("fullName", "X"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationCreatesMemberAndLogsIn() throws Exception {
        int n = unique();
        mvc.perform(post("/register").with(csrf())
                        .param("fullName", "Người mới").param("email", "new" + n + "@test.vn").param("phone", "0912345678")
                        .param("birthDate", "2001-02-03").param("password", "abc123").param("confirmPassword", "abc123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(cookie().exists("BB_TOKEN"));
        mvc.perform(post("/register").with(csrf())
                        .param("fullName", "Người mới").param("email", "new" + n + "@test.vn")
                        .param("birthDate", "2001-02-03").param("password", "abc123").param("confirmPassword", "abc123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("đã được đăng ký")));
    }
}
