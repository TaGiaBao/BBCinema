package com.bbcinema.integration;

import com.bbcinema.entity.User;
import jakarta.servlet.http.Cookie;
import com.bbcinema.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void publicPagesAreOpen() throws Exception {
        showtimeTomorrow();
        for (String path : new String[]{"/", "/movies", "/schedule", "/prices", "/news", "/cinemas", "/login", "/register", "/internal/login"}) {
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
        // Counter staff work inside a till shift: without one every counter page leads to "open shift".
        mvc.perform(get("/staff").cookie(authCookie(staff))).andExpect(redirectedUrl("/staff/shift/open"));
        mvc.perform(get("/staff/shift/open").cookie(authCookie(staff))).andExpect(status().isOk());
        openShift(staff, 0);
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
    void registrationCreatesMemberWaitingForEmailConfirmation() throws Exception {
        int n = unique();
        mvc.perform(post("/register").with(csrf())
                        .param("fullName", "Người mới").param("email", "new" + n + "@test.vn").param("phone", "0912345678")
                        .param("birthDate", "2001-02-03").param("password", "abc123").param("confirmPassword", "abc123"))
                .andExpect(redirectedUrl("/register/check-email"))
                .andExpect(cookie().doesNotExist("BB_TOKEN"));
        mvc.perform(post("/register").with(csrf())
                        .param("fullName", "Người mới").param("email", "new" + n + "@test.vn")
                        .param("birthDate", "2001-02-03").param("password", "abc123").param("confirmPassword", "abc123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("đã được đăng ký")));
    }
    @Test
    void internalAreasRedirectToInternalLogin() throws Exception {
        mvc.perform(get("/admin/movies"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/login?redirect=%2Fadmin%2Fmovies"));
        mvc.perform(get("/staff/checkin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/login?redirect=%2Fstaff%2Fcheckin"));
        mvc.perform(get("/staff"))
                .andExpect(redirectedUrl("/internal/login?redirect=%2Fstaff"));
        // A path that only starts with the same letters is not part of the internal area.
        mvc.perform(get("/administrator"))
                .andExpect(redirectedUrl("/login?redirect=%2Fadministrator"));
    }

    @Test
    void internalLoginAcceptsStaffAndAdmin() throws Exception {
        User staff = account(Role.STAFF);
        User admin = account(Role.ADMIN);
        mvc.perform(post("/internal/login").with(csrf()).param("email", staff.getEmail()).param("password", "Secret@123"))
                .andExpect(redirectedUrl("/staff"))
                .andExpect(cookie().httpOnly("BB_TOKEN", true));
        mvc.perform(post("/internal/login").with(csrf()).param("email", admin.getEmail()).param("password", "Secret@123"))
                .andExpect(redirectedUrl("/admin"));
        mvc.perform(post("/internal/login").with(csrf()).param("email", staff.getEmail()).param("password", "Secret@123")
                        .param("redirect", "/staff/checkin"))
                .andExpect(redirectedUrl("/staff/checkin"));
    }

    @Test
    void internalLoginRefusesMembers() throws Exception {
        User member = member(20);
        mvc.perform(post("/internal/login").with(csrf()).param("email", member.getEmail()).param("password", "Secret@123"))
                .andExpect(status().isOk())
                .andExpect(cookie().doesNotExist("BB_TOKEN"))
                .andExpect(content().string(containsString("chỉ dành cho nhân viên và quản trị viên")));
    }

    @Test
    void customerLoginRefusesStaffAndAdmin() throws Exception {
        for (Role role : new Role[]{Role.STAFF, Role.ADMIN}) {
            User user = account(role);
            mvc.perform(post("/login").with(csrf()).param("email", user.getEmail()).param("password", "Secret@123"))
                    .andExpect(status().isOk())
                    .andExpect(cookie().doesNotExist("BB_TOKEN"))
                    .andExpect(content().string(containsString("Vui lòng dùng trang đăng nhập nội bộ")));
        }
    }

    @Test
    void internalLoginPageHasNoRegisterLinkAndShowsDemoStaffAccounts() throws Exception {
        mvc.perform(get("/internal/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/register"))))
                .andExpect(content().string(containsString("staff@bbcinema.vn")));
        mvc.perform(get("/login"))
                .andExpect(content().string(not(containsString("staff@bbcinema.vn"))))
                .andExpect(content().string(not(containsString("admin@bbcinema.vn"))));
    }

    @Test
    void customerPagesDoNotLinkToInternalArea() throws Exception {
        showtimeTomorrow();
        User admin = account(Role.ADMIN);
        mvc.perform(get("/").cookie(authCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/admin\""))))
                .andExpect(content().string(not(containsString("href=\"/staff\""))));
    }

    @Test
    void signedInStaffIsSentFromInternalLoginToTheirHome() throws Exception {
        User staff = account(Role.STAFF);
        mvc.perform(get("/internal/login").cookie(authCookie(staff)))
                .andExpect(redirectedUrl("/staff"));
    }

    @Test
    void logoutReturnsToTheLoginPageOfTheArea() throws Exception {
        User staff = account(Role.STAFF);
        mvc.perform(post("/logout").with(csrf()).cookie(authCookie(staff)).param("area", "internal"))
                .andExpect(redirectedUrl("/internal/login?logout"));
        User member = member(20);
        mvc.perform(post("/logout").with(csrf()).cookie(authCookie(member)))
                .andExpect(redirectedUrl("/?logout"));
    }

    @Test
    void staffCannotLogOutDuringAShiftAndTheManagerClosesItWithTheDrawerCount() throws Exception {
        User staff = account(Role.STAFF);
        User manager = account(Role.ADMIN);
        Cookie cookie = authCookie(staff);
        mvc.perform(post("/staff/shift/open").with(csrf()).cookie(cookie).param("openingFloat", "500000")
                        .param("managerEmail", manager.getEmail()).param("managerPassword", "wrong"))
                .andExpect(redirectedUrl("/staff/shift/open"))
                .andExpect(flash().attribute("error", containsString("không đúng")));
        mvc.perform(post("/staff/shift/open").with(csrf()).cookie(cookie).param("openingFloat", "500000")
                        .param("managerEmail", manager.getEmail()).param("managerPassword", "Secret@123"))
                .andExpect(redirectedUrl("/staff"));

        // No logout button, and a logout request is refused while the shift is open.
        mvc.perform(get("/staff").cookie(cookie))
                .andExpect(content().string(containsString("href=\"/staff/shift/close\"")))
                .andExpect(content().string(not(containsString(">Đăng xuất</button>"))));
        mvc.perform(post("/logout").with(csrf()).cookie(cookie).param("area", "internal"))
                .andExpect(redirectedUrl("/staff/shift/close"))
                .andExpect(cookie().doesNotExist("BB_TOKEN"));

        mvc.perform(get("/staff/shift/close").cookie(cookie))
                .andExpect(content().string(containsString("Tiền mặt phải có trong két")))
                .andExpect(content().string(containsString("500.000đ")));
        mvc.perform(post("/staff/shift/close").with(csrf()).cookie(cookie).param("countedCash", "500000")
                        .param("managerEmail", manager.getEmail()).param("managerPassword", "Secret@123"))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("BB_TOKEN", 0))
                .andExpect(content().string(containsString("Đã kết ca và đăng xuất")))
                .andExpect(content().string(containsString("Khớp")));
        assertThat(shiftService.findOpen(staff.getId())).isEmpty();

        // The manager sees the shift in the admin area.
        Cookie admin = authCookie(manager);
        mvc.perform(get("/admin/shifts").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(staff.getFullName())));
        Long id = shiftService.recent().get(0).getId();
        mvc.perform(get("/admin/shifts/" + id).cookie(admin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Biên bản ca bán vé #" + id)));
        mvc.perform(get("/admin/shifts").cookie(authCookie(staff))).andExpect(status().isForbidden());
    }

    @Test
    void managerClosesAShiftFromTheAdminAreaWhenTheStaffMemberHasLeft() throws Exception {
        User staff = account(Role.STAFF);
        openShift(staff, 300_000);
        Long id = shiftService.findOpen(staff.getId()).orElseThrow().getId();
        Cookie admin = authCookie(account(Role.ADMIN));

        mvc.perform(get("/admin/shifts").cookie(admin))
                .andExpect(content().string(containsString("href=\"/admin/shifts/" + id + "/close\"")));
        mvc.perform(get("/admin/shifts/" + id + "/close").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/admin/shifts/" + id + "/close\"")))
                .andExpect(content().string(not(containsString("name=\"managerPassword\""))));
        mvc.perform(post("/admin/shifts/" + id + "/close").with(csrf()).cookie(admin).param("countedCash", "290000")
                        .param("note", "Nhân viên bỏ về không bàn giao"))
                .andExpect(redirectedUrl("/admin/shifts"))
                .andExpect(flash().attribute("success", containsString("Đã kết ca #" + id)));

        var closed = shiftService.getDetailed(id);
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.getDifference()).isEqualByComparingTo("-10000");
        // Closing twice is refused; staff coming back must have a new shift opened.
        mvc.perform(post("/admin/shifts/" + id + "/close").with(csrf()).cookie(admin).param("countedCash", "0"))
                .andExpect(flash().attribute("error", containsString("đã kết")));
        mvc.perform(get("/staff").cookie(authCookie(staff))).andExpect(redirectedUrl("/staff/shift/open"));
        // Staff cannot use the admin close.
        mvc.perform(post("/admin/shifts/" + id + "/close").with(csrf()).cookie(authCookie(staff)).param("countedCash", "0"))
                .andExpect(status().isForbidden());
    }
}
