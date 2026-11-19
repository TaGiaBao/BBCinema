package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.PointTransaction;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.PointType;
import com.bbcinema.repository.PointTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reward points. Members earn a percentage of what they pay (default 5%), counted in points of
 * 1,000 VND, and can spend points on later orders up to a share of the order (default 50%).
 */
@Service
@RequiredArgsConstructor
public class PointService {

    private final PointTransactionRepository pointTransactionRepository;
    private final AppProperties properties;
    private final Clock clock;

    /** Points earned for paying {@code amount}, e.g. 5% of 200,000đ = 10,000đ = 10 points. */
    public int pointsEarnedFor(BigDecimal amount) {
        BigDecimal value = amount.multiply(BigDecimal.valueOf(properties.points().earnPercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        return value.divide(BigDecimal.valueOf(properties.points().valuePerPoint()), 0, RoundingMode.DOWN).intValue();
    }

    /** Highest number of points the member may spend on an order of {@code orderAmount}. */
    public int maxRedeemable(User user, BigDecimal orderAmount) {
        BigDecimal cap = orderAmount.multiply(BigDecimal.valueOf(properties.points().maxRedeemPercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        int byOrder = cap.divide(BigDecimal.valueOf(properties.points().valuePerPoint()), 0, RoundingMode.DOWN).intValue();
        return Math.max(0, Math.min(user.getPoints(), byOrder));
    }

    public BigDecimal valueOf(int points) {
        return BigDecimal.valueOf((long) points * properties.points().valuePerPoint());
    }

    @Transactional
    public void record(User user, Booking booking, PointType type, int points, String note) {
        if (points == 0) {
            return;
        }
        user.setPoints(Math.max(0, user.getPoints() + points));
        PointTransaction tx = new PointTransaction();
        tx.setUser(user);
        tx.setBooking(booking);
        tx.setType(type);
        tx.setPoints(points);
        tx.setNote(note);
        tx.setCreatedAt(LocalDateTime.now(clock));
        pointTransactionRepository.save(tx);
    }

    @Transactional(readOnly = true)
    public List<PointTransaction> history(Long userId) {
        return pointTransactionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
