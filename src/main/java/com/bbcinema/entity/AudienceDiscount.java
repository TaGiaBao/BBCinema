package com.bbcinema.entity;

import com.bbcinema.entity.enums.AudienceType;
import com.bbcinema.entity.enums.DiscountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Discount of one audience ticket type (HSSV, children, seniors), set by the general manager on /admin/prices. */
@Entity
@Table(name = "audience_discounts")
@Getter
@Setter
@NoArgsConstructor
public class AudienceDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, unique = true, length = 10)
    private AudienceType audienceType;

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 10)
    private DiscountType discountType = DiscountType.PERCENT;

    /** Percent (1-100) or amount in VND per seat, depending on {@link #discountType}. */
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 0)
    private BigDecimal discountValue = BigDecimal.ZERO;

    /** Whether the type can also be sold for showtimes on a public holiday. */
    @Column(name = "on_holidays", nullable = false)
    private boolean onHolidays = false;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AudienceDiscount(AudienceType audienceType, DiscountType discountType, long value) {
        this.audienceType = audienceType;
        this.discountType = discountType;
        this.discountValue = BigDecimal.valueOf(value);
    }
}
