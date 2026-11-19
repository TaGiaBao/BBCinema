package com.bbcinema.service;

import com.bbcinema.dto.request.PromotionForm;
import com.bbcinema.entity.Promotion;
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

    public List<Promotion> findAll() {
        return promotionRepository.findAllByOrderByValidToDesc();
    }

    public Promotion getById(Long id) {
        return promotionRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy mã giảm giá"));
    }

    /** Finds a code and checks that it can be used today for an order of the given value. */
    public Promotion findUsable(String code, BigDecimal orderAmount) {
        Promotion promotion = promotionRepository.findByCodeIgnoreCase(code == null ? "" : code.trim())
                .orElseThrow(() -> new BusinessException("Mã giảm giá không tồn tại"));
        checkUsable(promotion, orderAmount, LocalDate.now(clock));
        return promotion;
    }

    static void checkUsable(Promotion promotion, BigDecimal orderAmount, LocalDate today) {
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

    /** Discount = percent of the order, capped by maxDiscount and by the order itself, rounded down to 1,000 VND. */
    public static BigDecimal discountFor(Promotion promotion, BigDecimal orderAmount) {
        BigDecimal discount = orderAmount.multiply(BigDecimal.valueOf(promotion.getDiscountPercent()))
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
