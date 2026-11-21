package com.bbcinema.service;

import com.bbcinema.dto.request.PromotionForm;
import com.bbcinema.entity.Promotion;
import com.bbcinema.entity.User;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.PromotionRepository;
import com.bbcinema.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final Clock clock;

    /** Refusal for a personal code (e.g. a birthday gift) used by another account. */
    public static final String NOT_YOUR_CODE = "Mã giảm giá này là ưu đãi cá nhân của một thành viên khác, tài khoản của bạn không dùng được.";

    /** The general codes managed on /admin/promotions. Personal codes (birthday gifts) are listed on their own page. */
    public List<Promotion> findAll() {
        return promotionRepository.findByOwnerIsNullOrderByValidToDesc();
    }

    public Promotion getById(Long id) {
        return promotionRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy mã giảm giá"));
    }

    /** Finds a code and checks that it can be used today for an order of the given value, with no account. */
    public Promotion findUsable(String code, BigDecimal orderAmount) {
        return findUsable(code, orderAmount, null);
    }

    /**
     * Finds a code and checks that {@code user} can use it today for an order of the given value. A personal code
     * is refused for every other account (and for orders with no account).
     */
    public Promotion findUsable(String code, BigDecimal orderAmount, User user) {
        Promotion promotion = promotionRepository.findByCodeIgnoreCase(code == null ? "" : code.trim())
                .orElseThrow(() -> new BusinessException("Mã giảm giá không tồn tại"));
        checkUsable(promotion, orderAmount, LocalDate.now(clock), user);
        return promotion;
    }

    static void checkUsable(Promotion promotion, BigDecimal orderAmount, LocalDate today) {
        checkUsable(promotion, orderAmount, today, null);
    }

    /** Throws when the code is personal and {@code user} is not its owner. */
    public static void checkOwner(Promotion promotion, User user) {
        if (promotion.getOwner() == null) {
            return;
        }
        if (user == null || user.getId() == null || !user.getId().equals(promotion.getOwner().getId())) {
            throw new BusinessException(NOT_YOUR_CODE);
        }
    }

    public static void checkUsable(Promotion promotion, BigDecimal orderAmount, LocalDate today, User user) {
        checkOwner(promotion, user);
        if (!promotion.isActive()) {
            throw new BusinessException("Mã giảm giá đã bị tắt");
        }
        if (today.isBefore(promotion.getValidFrom()) || today.isAfter(promotion.getValidTo())) {
            throw new BusinessException("Mã giảm giá không trong thời gian áp dụng");
        }
        if (promotion.getUsageLimit() != null && promotion.getUsedCount() >= promotion.getUsageLimit()) {
            throw new BusinessException("Mã giảm giá đã hết lượt sử dụng");
        }
        if (promotion.getMinOrder() != null && orderAmount.compareTo(promotion.getMinOrder()) < 0) {
            throw new BusinessException("Đơn hàng cần tối thiểu " + String.format(Locale.ROOT, "%,d", promotion.getMinOrder().longValue())
                    .replace(',', '.') + "đ để dùng mã này");
        }
    }

    /**
     * Discount = the fixed amount, or the percent of the order capped by maxDiscount; never more than the order
     * itself, rounded down to 1,000 VND.
     */
    public static BigDecimal discountFor(Promotion promotion, BigDecimal orderAmount) {
        BigDecimal discount = promotion.getDiscountAmount() != null ? promotion.getDiscountAmount()
                : orderAmount.multiply(BigDecimal.valueOf(promotion.getDiscountPercent()))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        if (promotion.getMaxDiscount() != null && discount.compareTo(promotion.getMaxDiscount()) > 0) {
            discount = promotion.getMaxDiscount();
        }
        discount = discount.divide(BigDecimal.valueOf(1000), 0, RoundingMode.DOWN).multiply(BigDecimal.valueOf(1000));
        return discount.min(orderAmount);
    }

    @Transactional
    public Promotion save(PromotionForm form) {
        if (form.getValidTo().isBefore(form.getValidFrom())) {
            throw new BusinessException("Ngày kết thúc phải sau ngày bắt đầu");
        }
        String code = form.getCode().trim().toUpperCase(Locale.ROOT);
        promotionRepository.findByCodeIgnoreCase(code)
                .filter(p -> !p.getId().equals(form.getId()))
                .ifPresent(p -> {
                    throw new BusinessException("Mã " + code + " đã tồn tại");
                });
        Promotion promotion = form.getId() == null ? new Promotion() : getById(form.getId());
        if (promotion.isPersonal()) {
            throw new BusinessException("Mã " + promotion.getCode() + " là ưu đãi cá nhân (quà sinh nhật), không sửa ở đây");
        }
        promotion.setCode(code);
        promotion.setDescription(TextUtil.blankToNull(form.getDescription()));
        promotion.setDiscountPercent(form.getDiscountPercent());
        promotion.setMaxDiscount(form.getMaxDiscount());
        promotion.setMinOrder(form.getMinOrder());
        promotion.setValidFrom(form.getValidFrom());
        promotion.setValidTo(form.getValidTo());
        promotion.setUsageLimit(form.getUsageLimit());
        promotion.setActive(form.isActive());
        return promotionRepository.save(promotion);
    }

    public PromotionForm toForm(Promotion p) {
        PromotionForm form = new PromotionForm();
        form.setId(p.getId());
        form.setCode(p.getCode());
        form.setDescription(p.getDescription());
        form.setDiscountPercent(p.getDiscountPercent());
        form.setMaxDiscount(p.getMaxDiscount());
        form.setMinOrder(p.getMinOrder());
        form.setValidFrom(p.getValidFrom());
        form.setValidTo(p.getValidTo());
        form.setUsageLimit(p.getUsageLimit());
        form.setActive(p.isActive());
        return form;
    }
}
