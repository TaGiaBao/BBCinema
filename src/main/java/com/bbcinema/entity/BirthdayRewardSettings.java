package com.bbcinema.entity;

import com.bbcinema.entity.enums.DiscountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The birthday gift the general manager sets on /admin/promotions/birthday. A single row with id {@value #SINGLETON_ID}:
 * there is one gift for the whole chain.
 */
@Entity
@Table(name = "birthday_reward_settings")
@Getter
@Setter
@NoArgsConstructor
public class BirthdayRewardSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false)
    private boolean enabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    private DiscountType discountType = DiscountType.PERCENT;

    /** Percent (1-100) or amount in VND, depending on {@link #discountType}. */
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 0)
    private BigDecimal discountValue = BigDecimal.valueOf(20);

    /** Cap of a percent discount in VND; null means no cap. Not used for a fixed amount. */
    @Column(name = "max_discount", precision = 12, scale = 0)
    private BigDecimal maxDiscount = BigDecimal.valueOf(50_000);

    @Column(name = "min_order", precision = 12, scale = 0)
    private BigDecimal minOrder;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
