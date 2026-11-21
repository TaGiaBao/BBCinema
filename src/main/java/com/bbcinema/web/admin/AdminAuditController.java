package com.bbcinema.web.admin;

import com.bbcinema.entity.enums.Role;
import com.bbcinema.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;

/** The audit log for the general manager: who did what in the internal area, filtered by person, role, day. */
@Controller
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    static final int PAGE_SIZE = 30;
    /** Without dates the page shows the last week. */
    static final int DEFAULT_DAYS = 7;

    private final AuditService auditService;
    private final Clock clock;

    @GetMapping
    public String list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String actor,
                       @RequestParam(required = false) Role role,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String outcome,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        model.addAttribute("page", auditService.search(start, end, outcome, role, actor, q,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("actor", actor);
        model.addAttribute("role", role);
        model.addAttribute("q", q);
        model.addAttribute("outcome", outcome);
        model.addAttribute("roles", Arrays.stream(Role.values()).filter(Role::isInternal).toList());
        model.addAttribute("menu", "audit");
        return "admin/audit";
    }
}
