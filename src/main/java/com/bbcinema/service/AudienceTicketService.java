package com.bbcinema.service;

import com.bbcinema.entity.AudienceDiscount;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.ShowtimeSeat;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.AudienceType;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.AudienceDiscountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Audience tickets (HSSV, children, seniors), sold at the counter only, where staff can ask for a student card or ID.
 * <p>
 * Rules: each type is switched on/off by the general manager, with a percent or fixed amount per seat and a choice
 * whether it is sold on public holidays. Couple seats are never discounted; child tickets only exist for films rated
 * P or K. A member's tier discount and audience tickets do not add up: {@link BookingService} keeps the better one.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AudienceTicketService {

    /** The discounted types, in the order they are shown. */
    public static final List<AudienceType> DISCOUNTED = List.of(AudienceType.STUDENT, AudienceType.CHILD, AudienceType.SENIOR);

    private final AudienceDiscountRepository repository;
    private final HolidayService holidayService;
    private final Clock clock;

    /** What staff can choose for one showtime: every type, and why it cannot be sold when it cannot. */
    public record Option(AudienceType type, boolean available, String reason, DiscountType discountType, BigDecimal value) {
        public String getLabel() {
            return type.getLabel();
        }

        /** "Giảm 20%" / "Giảm 30.000đ/vé", or why the type is not sold. */
        public String getDiscountText() {
            if (!type.isDiscounted()) {
                return "Giá thường";
            }
            return available ? describe(discountType, value) : reason;
        }

        public boolean isPercent() {
            return discountType == DiscountType.PERCENT;
        }
    }

    // ------------------------------------------------------------------ settings

    /** The three discounted types; a type with no row yet (database made by Hibernate) shows its default. */
    public List<AudienceDiscount> settings() {
        Map<AudienceType, AudienceDiscount> byType = new EnumMap<>(AudienceType.class);
        repository.findAll().forEach(d -> byType.put(d.getAudienceType(), d));
        List<AudienceDiscount> out = new ArrayList<>();
        for (AudienceType type : DISCOUNTED) {
            out.add(byType.getOrDefault(type, defaultFor(type)));
        }
        return out;
    }

    static AudienceDiscount defaultFor(AudienceType type) {
        return new AudienceDiscount(type, DiscountType.PERCENT, type == AudienceType.STUDENT ? 20 : 30);
    }

    /** Input of one row of the admin form. */
    public record Setting(boolean enabled, DiscountType discountType, BigDecimal value, boolean onHolidays) { }

    @Transactional
    public void save(Map<AudienceType, Setting> settings) {
        settings.forEach(AudienceTicketService::validate);
        LocalDateTime now = LocalDateTime.now(clock);
        settings.forEach((type, s) -> {
            AudienceDiscount d = repository.findByAudienceType(type).orElseGet(() -> defaultFor(type));
            d.setEnabled(s.enabled());
            d.setDiscountType(s.discountType());
            d.setDiscountValue(s.value());
            d.setOnHolidays(s.onHolidays());
            d.setUpdatedAt(now);
            repository.save(d);
        });
    }

    static void validate(AudienceType type, Setting s) {
        if (!type.isDiscounted()) {
            throw new BusinessException("Vé người lớn không có mức giảm");
        }
        String name = "Vé " + type.getLabel();
        if (s.discountType() == null || s.value() == null) {
            throw new BusinessException(name + ": chọn cách giảm và nhập mức giảm");
        }
        if (s.value().signum() <= 0) {
            throw new BusinessException(name + ": mức giảm phải lớn hơn 0");
        }
        if (s.discountType() == DiscountType.PERCENT) {
            if (s.value().compareTo(BigDecimal.valueOf(100)) >= 0 || s.value().stripTrailingZeros().scale() > 0) {
                throw new BusinessException(name + ": giảm theo phần trăm là số nguyên từ 1% đến 99%");
            }
        } else if (s.value().compareTo(BigDecimal.valueOf(1000)) < 0) {
            throw new BusinessException(name + ": số tiền giảm tối thiểu là 1.000đ");
        }
    }

    // ------------------------------------------------------------------ rules for one showtime

    /** Adult first, then each discounted type with whether it can be sold for this showtime. */
    public List<Option> optionsFor(Showtime showtime) {
        boolean holiday = isHoliday(showtime);
        List<Option> out = new ArrayList<>();
        out.add(new Option(AudienceType.ADULT, true, null, null, BigDecimal.ZERO));
        for (AudienceDiscount d : settings()) {
            String reason = reasonNotSold(d, showtime, holiday);
            out.add(new Option(d.getAudienceType(), reason == null, reason, d.getDiscountType(), d.getDiscountValue()));
        }
        return out;
    }

    /** Why the type cannot be sold for this showtime, or null when it can. */
    static String reasonNotSold(AudienceDiscount d, Showtime showtime, boolean holiday) {
        if (!d.isEnabled()) {
            return "Đang tắt";
        }
        if (holiday && !d.isOnHolidays()) {
            return "Không áp dụng ngày lễ";
        }
        if (d.getAudienceType() == AudienceType.CHILD && !forChildren(showtime.getMovie().getAgeRating())) {
            return "Chỉ cho phim P, K";
        }
        return null;
    }

    static boolean forChildren(AgeRating rating) {
        return rating == AgeRating.P || rating == AgeRating.K;
    }

    private boolean isHoliday(Showtime showtime) {
        LocalDate day = showtime.getStartTime().toLocalDate();
        return holidayService.holidayDatesIn(List.of(day)).contains(day);
    }

    // ------------------------------------------------------------------ applying to a sale

    /**
     * Sets each held seat's ticket type and discount from staff's choice (seat id to type; missing = adult) and the
     * booking's audience total. Refuses a type that cannot be sold for this showtime or seat.
     */
    public void apply(Booking booking, Map<Long, AudienceType> chosen) {
        Map<AudienceType, Option> options = new EnumMap<>(AudienceType.class);
        optionsFor(booking.getShowtime()).forEach(o -> options.put(o.type(), o));
        BigDecimal total = BigDecimal.ZERO;
        for (ShowtimeSeat ss : booking.getSeats()) {
            AudienceType type = chosen == null ? null : chosen.get(ss.getId());
            if (type == null || !type.isDiscounted()) {
                ss.setAudienceType(null);
                ss.setAudienceDiscount(null);
                continue;
            }
            Option option = options.get(type);
            if (!option.available()) {
                throw new BusinessException("Ghế " + ss.getSeat().getLabel() + ": không bán được vé " + type.getLabel()
                        + " cho suất này (" + option.reason().toLowerCase(Locale.ROOT) + ")");
            }
            if (ss.getSeat().getType() == SeatType.COUPLE) {
                throw new BusinessException("Ghế đôi " + ss.getSeat().getLabel() + " không áp dụng vé " + type.getLabel());
            }
            BigDecimal discount = discountOn(option.discountType(), option.value(), ss.getPrice());
            ss.setAudienceType(type);
            ss.setAudienceDiscount(discount);
            total = total.add(discount);
        }
        booking.setAudienceDiscount(total);
    }

    /** Percent of the seat price rounded down to 1,000đ, or the fixed amount; never more than the price. */
    public static BigDecimal discountOn(DiscountType type, BigDecimal value, BigDecimal price) {
        BigDecimal discount = type == DiscountType.AMOUNT ? value
                : price.multiply(value).divide(BigDecimal.valueOf(100_000), 0, RoundingMode.DOWN)
                        .multiply(BigDecimal.valueOf(1000));
        return discount.min(price).max(BigDecimal.ZERO);
    }

    public static String describe(DiscountType type, BigDecimal value) {
        if (type == DiscountType.AMOUNT) {
            return "Giảm " + String.format(Locale.ROOT, "%,d", value.longValue()).replace(',', '.') + "đ/vé";
        }
        return "Giảm " + value.stripTrailingZeros().toPlainString() + "%";
    }
}
