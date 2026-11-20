package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A birthday gift given to a member: the personal promotion code of that year. The unique key on (user, year) makes
 * issuing idempotent, even with two app instances or a birth date changed later in the year.
 */
@Entity
@Table(name = "birthday_rewards", uniqueConstraints = {
        @UniqueConstraint(name = "uk_birthday_rewards_user_year", columnNames = {"user_id", "reward_year"}),
        @UniqueConstraint(name = "uk_birthday_rewards_promotion", columnNames = {"promotion_id"})})
@Getter
@Setter
@NoArgsConstructor
public class BirthdayReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "reward_year", nullable = false)
    private int rewardYear;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;
}
