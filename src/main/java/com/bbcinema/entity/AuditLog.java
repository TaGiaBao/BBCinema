package com.bbcinema.entity;

import com.bbcinema.entity.enums.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One action taken in the internal area: who (e-mail and role at that moment), what, on which record, whether it
 * worked, from which address. The actor is kept as text, so the row still reads right after the account changes.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_created_at", columnList = "created_at"),
        @Index(name = "idx_audit_logs_actor_email", columnList = "actor_email")})
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    public static final int DETAIL_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_email", nullable = false, length = 150)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 10)
    private Role actorRole;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(length = 100)
    private String target;

    @Column(length = DETAIL_LENGTH)
    private String detail;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(length = 255)
    private String path;

    @Column(length = 45)
    private String ip;
}
