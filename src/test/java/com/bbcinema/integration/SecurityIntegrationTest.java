package com.bbcinema.integration;

import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.User;
import java.math.BigDecimal;
import jakarta.servlet.http.Cookie;
import com.bbcinema.entity.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityIntegrationTest extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired com.bbcinema.service.InventoryService inventoryService;

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
        User manager = account(Role.MANAGER);
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

        // Managers see the shift in the admin area.
        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(get("/admin/shifts").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(staff.getFullName())));
        Long id = shiftService.recent().get(0).getId();
        mvc.perform(get("/admin/shifts/" + id).cookie(admin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Biên bản ca bán vé #" + id)));
        mvc.perform(get("/admin/shifts").cookie(authCookie(staff))).andExpect(status().isForbidden());
    }

    @Test
    void counterManagerClosesAShiftLeftOpenAtTheCounterAndTheGeneralManagerCannot() throws Exception {
        Cinema cinema = showtimeTomorrow().getRoom().getCinema();
        User staff = account(Role.STAFF);
        User manager = account(Role.MANAGER);
        manager.setCinema(cinema);
        userRepository.save(manager);
        User otherManager = account(Role.MANAGER);
        Cinema other = new Cinema();
        other.setName("Rạp khác " + unique());
        other.setAddress("Hà Nội");
        otherManager.setCinema(cinemaRepository.save(other));
        userRepository.save(otherManager);
        // The shift is opened at this cinema's counter by its counter manager; another cinema's manager cannot.
        assertThatThrownBy(() -> shiftService.open(staff.getId(), cinema.getId(), otherManager.getEmail(), "Secret@123", BigDecimal.ZERO))
                .hasMessageContaining("rạp khác");
        Long id = shiftService.open(staff.getId(), cinema.getId(), manager.getEmail(), "Secret@123", BigDecimal.valueOf(300_000)).getId();

        // No closing from the admin area, for anyone.
        Cookie general = authCookie(account(Role.ADMIN));
        mvc.perform(get("/admin/shifts").cookie(general))
                .andExpect(content().string(not(containsString("/close"))));
        mvc.perform(post("/admin/shifts/" + id + "/close").with(csrf()).cookie(general).param("countedCash", "0"))
                .andExpect(status().is4xxClientError());
        // The general manager never works the till.
        mvc.perform(get("/staff/shifts").cookie(general)).andExpect(status().isForbidden());

        // The counter manager, signed in on the counter computer, counts the drawer and closes it.
        Cookie counter = authCookie(manager);
        mvc.perform(get("/staff").cookie(counter)).andExpect(redirectedUrl("/staff/shifts"));
        mvc.perform(get("/staff/shifts").cookie(counter))
                .andExpect(content().string(containsString("href=\"/staff/shifts/" + id + "/close\"")));
        mvc.perform(get("/staff/shifts").cookie(authCookie(otherManager)))
                .andExpect(content().string(not(containsString("/staff/shifts/" + id + "/close"))));
        mvc.perform(get("/staff/shifts/" + id + "/close").cookie(counter))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"managerPassword\""))));
        mvc.perform(post("/staff/shifts/" + id + "/close").with(csrf()).cookie(counter).param("countedCash", "290000")
                        .param("note", "Nhân viên bỏ về không bàn giao"))
                .andExpect(redirectedUrl("/staff/shifts"))
                .andExpect(flash().attribute("success", containsString("Đã kết ca #" + id)));
        var closed = shiftService.getDetailed(id);
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.getClosedBy().getId()).isEqualTo(manager.getId());
        assertThat(closed.getDifference()).isEqualByComparingTo("-10000");
        mvc.perform(get("/staff").cookie(authCookie(staff))).andExpect(redirectedUrl("/staff/shift/open"));
        // Counter managers only see their own cinema's shift reports.
        mvc.perform(get("/admin/shifts/" + id).cookie(counter)).andExpect(status().isOk());
        mvc.perform(get("/admin/shifts/" + id).cookie(authCookie(otherManager))).andExpect(status().isNotFound());
    }

    @Test
    void counterManagerHasTheDayToDayPagesAndTheGeneralManagerTheRest() throws Exception {
        User manager = account(Role.MANAGER);
        Cookie counter = authCookie(manager);
        for (String page : new String[]{"/admin", "/admin/bookings", "/admin/movies", "/admin/showtimes", "/admin/showtimes/auto",
                "/admin/reviews", "/admin/shifts", "/admin/inventory", "/staff/checkin"}) {
            mvc.perform(get(page).cookie(counter)).andExpect(status().isOk());
        }
        for (String page : new String[]{"/admin/cinemas", "/admin/prices", "/admin/combos", "/admin/promotions", "/admin/posts",
                "/admin/banners", "/admin/users"}) {
            mvc.perform(get(page).cookie(counter)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/reports/revenue?from=2026-01-01&to=2026-01-02").header("Authorization", bearer(manager)))
                .andExpect(status().isForbidden());
        Cookie general = authCookie(account(Role.ADMIN));
        mvc.perform(get("/admin/users").cookie(general)).andExpect(status().isOk());
        mvc.perform(get("/staff").cookie(general)).andExpect(status().isForbidden());
        mvc.perform(post("/internal/login").with(csrf()).param("email", manager.getEmail()).param("password", "Secret@123"))
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    void generalManagerGivesOutInternalAccounts() throws Exception {
        Cinema cinema = showtimeTomorrow().getRoom().getCinema();
        Cookie general = authCookie(account(Role.ADMIN));
        String email = "ql" + unique() + "@bbcinema.vn";
        mvc.perform(post("/admin/users/new").with(csrf()).cookie(general).param("email", email).param("fullName", "Quản lý mới")
                        .param("password", "Secret@123").param("role", "MANAGER"))
                .andExpect(flash().attribute("error", containsString("phải thuộc một rạp")));
        mvc.perform(post("/admin/users/new").with(csrf()).cookie(general).param("email", email).param("fullName", "Quản lý mới")
                        .param("password", "Secret@123").param("role", "MANAGER").param("cinemaId", cinema.getId().toString()))
                .andExpect(flash().attribute("success", containsString(email)));
        User created = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.MANAGER);
        assertThat(created.getCinema().getId()).isEqualTo(cinema.getId());
        mvc.perform(post("/admin/users/new").with(csrf()).cookie(general).param("email", "x" + email).param("fullName", "Khách")
                        .param("password", "Secret@123").param("role", "USER"))
                .andExpect(flash().attribute("error", containsString("Chỉ cấp tài khoản")));
        mvc.perform(post("/admin/users/new").with(csrf()).cookie(authCookie(account(Role.MANAGER))).param("email", "y" + email)
                        .param("fullName", "X").param("password", "Secret@123").param("role", "STAFF"))
                .andExpect(status().isForbidden());
    }

    @Test
    void counterManagerRequestsStockAndTheGeneralManagerApprovesIt() throws Exception {
        Cinema cinema = showtimeTomorrow().getRoom().getCinema();
        User manager = account(Role.MANAGER);
        manager.setCinema(cinema);
        userRepository.save(manager);
        Cookie counter = authCookie(manager);
        Cookie general = authCookie(account(Role.ADMIN));
        String itemName = "Ngô test " + unique();
        // Only the general manager keeps the item list.
        mvc.perform(post("/admin/inventory/items").with(csrf()).cookie(counter).param("name", itemName).param("unit", "kg"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/inventory/items").with(csrf()).cookie(general).param("name", itemName).param("unit", "kg"))
                .andExpect(flash().attribute("success", containsString(itemName)));
        Long itemId = inventoryService.items().stream().filter(i -> i.getName().equals(itemName)).findFirst().orElseThrow().getId();

        mvc.perform(post("/admin/inventory/requests").with(csrf()).cookie(counter).param("note", "trống"))
                .andExpect(flash().attribute("error", containsString("ít nhất một mặt hàng")));
        mvc.perform(post("/admin/inventory/requests").with(csrf()).cookie(counter).param("qty_" + itemId, "25").param("note", "NCC A"))
                .andExpect(flash().attribute("success", containsString("cho quản lý tổng duyệt")));
        var request = inventoryService.requestsFor(manager).get(0);
        // Waiting for approval: no stock yet; the counter manager cannot approve their own note.
        assertThat(inventoryService.stockOf(cinema.getId()).entrySet().stream()
                .filter(e -> e.getKey().getId().equals(itemId)).findFirst().orElseThrow().getValue()).isZero();
        mvc.perform(post("/admin/inventory/requests/" + request.getId() + "/review").with(csrf()).cookie(counter).param("decision", "approve"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/inventory/requests/" + request.getId() + "/review").with(csrf()).cookie(general).param("decision", "reject"))
                .andExpect(flash().attribute("error", containsString("lý do")));
        mvc.perform(post("/admin/inventory/requests/" + request.getId() + "/review").with(csrf()).cookie(general).param("decision", "approve"))
                .andExpect(flash().attribute("success", containsString("Đã duyệt")));
        assertThat(inventoryService.stockOf(cinema.getId()).entrySet().stream()
                .filter(e -> e.getKey().getId().equals(itemId)).findFirst().orElseThrow().getValue()).isEqualTo(25);
        mvc.perform(post("/admin/inventory/requests/" + request.getId() + "/review").with(csrf()).cookie(general).param("decision", "approve"))
                .andExpect(flash().attribute("error", containsString("đã được xử lý")));
        mvc.perform(get("/admin/inventory").cookie(counter)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Gửi quản lý tổng duyệt")));
        mvc.perform(get("/admin/inventory").cookie(general)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Thêm mặt hàng")));
    }
}
