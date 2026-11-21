package com.bbcinema.integration;

import com.bbcinema.entity.AuditLog;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditLogIntegrationTest extends IntegrationTestBase {

    @Autowired AuditService auditService;
    @Autowired Clock clock;
    @Autowired com.bbcinema.repository.GenreRepository genreRepository;

    private List<AuditLog> logOf(String email) {
        LocalDate today = LocalDate.now(clock);
        return auditService.search(today.minusDays(1), today.plusDays(1), null, null, email, null,
                PageRequest.of(0, 50)).getContent();
    }

    @Test
    void adminChangeIsLoggedWithWhoWhatAndTheMessage() throws Exception {
        User admin = account(Role.ADMIN);
        String genre = "Thể loại nhật ký " + unique();

        mvc.perform(post("/admin/movies/genres").with(csrf()).cookie(authCookie(admin)).param("name", genre))
                .andExpect(status().is3xxRedirection());

        List<AuditLog> rows = logOf(admin.getEmail());
        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo("Thêm thể loại");
        assertThat(row.getActorRole()).isEqualTo(Role.ADMIN);
        assertThat(row.getActorId()).isEqualTo(admin.getId());
        assertThat(row.isSuccess()).isTrue();
        assertThat(row.getDetail()).contains(genre);
        assertThat(row.getHttpMethod()).isEqualTo("POST");
        assertThat(row.getPath()).isEqualTo("/admin/movies/genres");
    }

    @Test
    void refusedActionIsLoggedAsFailedWithTheReasonAndTheRecord() throws Exception {
        User staff = staffOnShift();

        mvc.perform(post("/staff/checkin").with(csrf()).cookie(authCookie(staff)).param("code", "ZZZZZZZZ"))
                .andExpect(status().is3xxRedirection());

        List<AuditLog> rows = logOf(staff.getEmail());
        assertThat(rows).extracting(AuditLog::getAction).containsExactly("Soát vé");
        AuditLog row = rows.get(0);
        assertThat(row.isSuccess()).isFalse();
        assertThat(row.getTarget()).isEqualTo("code=ZZZZZZZZ");
        assertThat(row.getDetail()).isNotBlank();
        assertThat(row.getActorRole()).isEqualTo(Role.STAFF);
    }

    @Test
    void pathVariablesNameTheRecord() throws Exception {
        User admin = account(Role.ADMIN);
        var genre = new com.bbcinema.entity.Genre();
        genre.setName("Xoá thử " + unique());
        Long id = genreRepository.save(genre).getId();

        mvc.perform(post("/admin/movies/genres/" + id + "/delete").with(csrf()).cookie(authCookie(admin)))
                .andExpect(status().is3xxRedirection());

        AuditLog row = logOf(admin.getEmail()).get(0);
        assertThat(row.getAction()).isEqualTo("Xoá thể loại");
        assertThat(row.getTarget()).isEqualTo("id=" + id);
        assertThat(row.isSuccess()).isTrue();
    }

    @Test
    void viewingPagesIsNotLogged() throws Exception {
        User admin = account(Role.ADMIN);
        mvc.perform(get("/admin/movies").cookie(authCookie(admin))).andExpect(status().isOk());
        mvc.perform(get("/admin/audit").cookie(authCookie(admin))).andExpect(status().isOk());
        assertThat(logOf(admin.getEmail())).isEmpty();
    }

    @Test
    void internalLoginAttemptsAreLoggedBothWays() throws Exception {
        User manager = account(Role.MANAGER);

        mvc.perform(post("/internal/login").with(csrf()).param("email", manager.getEmail()).param("password", "wrong"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/login").with(csrf()).param("email", manager.getEmail()).param("password", "Secret@123"))
                .andExpect(status().is3xxRedirection());

        List<AuditLog> rows = logOf(manager.getEmail());
        assertThat(rows).extracting(AuditLog::getAction).containsOnly("Đăng nhập nội bộ");
        // Newest first: the successful attempt, then the wrong password.
        assertThat(rows).extracting(AuditLog::isSuccess).containsExactly(true, false);
        assertThat(rows.get(1).getActorId()).isNull();
        assertThat(rows.get(1).getDetail()).isNotBlank();
        assertThat(rows.get(0).getActorRole()).isEqualTo(Role.MANAGER);
    }

    @Test
    void searchFiltersByOutcomeRoleAndKeyword() throws Exception {
        User staff = staffOnShift();
        mvc.perform(post("/staff/checkin").with(csrf()).cookie(authCookie(staff)).param("code", "QQQQQQQQ"));
        LocalDate today = LocalDate.now(clock);
        PageRequest page = PageRequest.of(0, 50);

        assertThat(auditService.search(today, today, AuditService.FAILED, Role.STAFF, staff.getEmail(), "qqqq", page)
                .getContent()).hasSize(1);
        assertThat(auditService.search(today, today, AuditService.OK, null, staff.getEmail(), null, page)
                .getContent()).isEmpty();
        assertThat(auditService.search(today, today, null, Role.ADMIN, staff.getEmail(), null, page)
                .getContent()).isEmpty();
        assertThat(auditService.search(today.minusDays(3), today.minusDays(1), null, null, staff.getEmail(), null, page)
                .getContent()).isEmpty();
    }

    @Test
    void onlyTheGeneralManagerSeesTheLog() throws Exception {
        User staff = staffOnShift();
        mvc.perform(post("/staff/checkin").with(csrf()).cookie(authCookie(staff)).param("code", "WWWWWWWW"));

        mvc.perform(get("/admin/audit").param("actor", staff.getEmail()).cookie(authCookie(account(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("code=WWWWWWWW")))
                .andExpect(content().string(containsString("Thất bại")));
        mvc.perform(get("/admin/audit").cookie(authCookie(account(Role.MANAGER)))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/audit").cookie(authCookie(staff))).andExpect(status().isForbidden());
    }
}
