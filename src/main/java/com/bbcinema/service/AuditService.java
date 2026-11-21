package com.bbcinema.service;

import com.bbcinema.entity.AuditLog;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Writes and searches the audit log of internal actions. Writing never breaks the action being logged. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String OK = "ok";
    public static final String FAILED = "fail";

    private final AuditLogRepository repository;
    private final Clock clock;

    /** What happened, as the interceptor or the login page saw it. */
    public record Entry(Long actorId, String actorEmail, Role actorRole, String action, String target,
                        String detail, boolean success, String httpMethod, String path, String ip) {
    }

    /** Saves in its own transaction, so an action that was rolled back is still logged as failed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Entry entry) {
        try {
            AuditLog row = new AuditLog();
            row.setCreatedAt(LocalDateTime.now(clock));
            row.setActorId(entry.actorId());
            String email = entry.actorEmail() == null || entry.actorEmail().isBlank() ? "?" : entry.actorEmail().trim();
            row.setActorEmail(cut(email, 150));
            row.setActorRole(entry.actorRole());
            row.setAction(cut(entry.action(), 100));
            row.setTarget(cut(entry.target(), 100));
            row.setDetail(cut(entry.detail(), AuditLog.DETAIL_LENGTH));
            row.setSuccess(entry.success());
            row.setHttpMethod(cut(entry.httpMethod(), 10));
            row.setPath(cut(entry.path(), 255));
            row.setIp(cut(entry.ip(), 45));
            repository.save(row);
        } catch (RuntimeException e) {
            log.warn("Could not write audit log entry {}: {}", entry.action(), e.getMessage());
        }
    }

    /** Filters the log, newest first. Both days are included; outcome is {@link #OK}, {@link #FAILED} or empty. */
    @Transactional(readOnly = true)
    public Page<AuditLog> search(LocalDate from, LocalDate to, String outcome, Role role, String actor,
                                 String keyword, Pageable pageable) {
        List<Boolean> outcomes = switch (outcome == null ? "" : outcome) {
            case OK -> List.of(true);
            case FAILED -> List.of(false);
            default -> List.of(true, false);
        };
        return repository.search(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), outcomes, role,
                blankToNull(actor), blankToNull(keyword), pageable);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
