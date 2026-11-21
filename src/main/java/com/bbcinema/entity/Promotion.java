package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "promotions")
@Getter
@Setter
@NoArgsConstructor
public class Promotion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(length = 255)
    private String description;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    /** Upper bound of the discount in VND; null means no cap. */
    @Column(name = "max_discount", precision = 12, scale = 0)
    private BigDecimal maxDiscount;

    @Column(name = "min_order", precision = 12, scale = 0)
    private BigDecimal minOrder;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    /** Total number of uses allowed; null means unlimited. */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(nullable = false)
    private boolean active = true;

    /** Fixed discount in VND; when set it is used instead of {@link #discountPercent}. Null for percent codes. */
    @Column(name = "discount_amount", precision = 12, scale = 0)
    private BigDecimal discountAmount;

    /**
     * The only member account allowed to use this code (personal codes such as the birthday gift); null for codes
     * anyone can use.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    public boolean isPersonal() {
        return owner != null;
    }

    /** True when every allowed use is taken (always false for unlimited codes). */
    public boolean isUsedUp() {
        return usageLimit != null && usedCount >= usageLimit;
    }
}
