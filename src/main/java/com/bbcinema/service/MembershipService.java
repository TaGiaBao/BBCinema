package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.MemberTier;
import com.bbcinema.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;

/** Works out a member's tier from what they spent recently, and the discount that tier gives. */
@Service
@RequiredArgsConstructor
public class MembershipService {

    private final BookingRepository bookingRepository;
    private final AppProperties properties;
    private final Clock clock;

    /** Where a member stands: tier, spending in the period, and what is needed for the next tier. */
    public record Status(MemberTier tier, BigDecimal spent, int discountPercent, MemberTier next, BigDecimal toNext) {
    }

    public BigDecimal spentRecently(Long userId) {
        AppProperties.Membership m = properties.membershipOrDefault();
        BigDecimal sum = bookingRepository.sumPaidByUserSince(userId, LocalDateTime.now(clock).minusDays(m.periodDays()));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public MemberTier tierOf(User user) {
        return user == null ? MemberTier.STANDARD : tierFor(spentRecently(user.getId()), properties.membershipOrDefault());
    }

    public Status status(User user) {
        AppProperties.Membership m = properties.membershipOrDefault();
        BigDecimal spent = spentRecently(user.getId());
        MemberTier tier = tierFor(spent, m);
        MemberTier next = tier == MemberTier.DIAMOND ? null : MemberTier.values()[tier.ordinal() + 1];
        BigDecimal toNext = next == null ? BigDecimal.ZERO : BigDecimal.valueOf(threshold(next, m)).subtract(spent).max(BigDecimal.ZERO);
        return new Status(tier, spent, percent(tier, m), next, toNext);
    }

    public int discountPercent(MemberTier tier) {
        return percent(tier, properties.membershipOrDefault());
    }

    /** Tier discount on an amount, rounded down to whole đồng. */
    public BigDecimal discountOn(MemberTier tier, BigDecimal amount) {
        int percent = discountPercent(tier);
        return amount.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
    }

    static MemberTier tierFor(BigDecimal spent, AppProperties.Membership m) {
        long value = spent.longValue();
        if (value >= m.diamondFrom()) {
            return MemberTier.DIAMOND;
        }
        if (value >= m.goldFrom()) {
            return MemberTier.GOLD;
        }
        return value >= m.silverFrom() ? MemberTier.SILVER : MemberTier.STANDARD;
    }

    static long threshold(MemberTier tier, AppProperties.Membership m) {
        return switch (tier) {
            case SILVER -> m.silverFrom();
            case GOLD -> m.goldFrom();
            case DIAMOND -> m.diamondFrom();
            default -> 0;
        };
    }

    static int percent(MemberTier tier, AppProperties.Membership m) {
        return switch (tier) {
            case SILVER -> m.silverPercent();
            case GOLD -> m.goldPercent();
            case DIAMOND -> m.diamondPercent();
            default -> 0;
        };
    }
}
