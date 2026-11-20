package com.bbcinema.entity;

import com.bbcinema.entity.enums.Role;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Current reward point balance. 1 point is worth 1,000 VND when redeemed. */
    @Column(nullable = false)
    private int points = 0;

    /**
     * When the member opened the link mailed at sign-up; null until then. Members cannot sign in with an
     * unconfirmed address, because tickets are sent to it. Staff, admins and Google accounts are confirmed at once.
     */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /** SHA-256 of the one-time sign-up confirmation token, and when that link stops working. */
    @Column(name = "verify_token_hash", length = 64)
    private String verifyTokenHash;

    @Column(name = "verify_token_expires_at")
    private LocalDateTime verifyTokenExpiresAt;

    /** SHA-256 of the one-time "forgot password" token, and when that link stops working. */
    @Column(name = "reset_token_hash", length = 64)
    private String resetTokenHash;

    @Column(name = "reset_token_expires_at")
    private LocalDateTime resetTokenExpiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /** Age in full years on the given date, or -1 when the birth date is unknown. */
    public int ageOn(LocalDate date) {
        if (birthDate == null) {
            return -1;
        }
        return Period.between(birthDate, date).getYears();
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }
}
