package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A counter staff member's shift at the till. A manager (ADMIN) opens it with the opening float and closes it
 * after counting the drawer; the totals of the shift are frozen into the row when it closes.
 */
@Entity
@Table(name = "counter_shifts")
@Getter
@Setter
@NoArgsConstructor
public class CounterShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id")
    private User staff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cinema_id")
    private Cinema cinema;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opened_by_id")
    private User openedBy;

    /** Small change put in the drawer by the manager at the start; it stays in the drawer at the end. */
    @Column(name = "opening_float", nullable = false, precision = 12, scale = 0)
    private BigDecimal openingFloat = BigDecimal.ZERO;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by_id")
    private User closedBy;

    /** Cash the manager counted in the drawer at closing (float included). */
    @Column(name = "counted_cash", precision = 12, scale = 0)
    private BigDecimal countedCash;

    // Totals frozen at closing time.
    @Column(name = "cash_sales")
    private Integer cashSales;

    @Column(name = "cash_received", precision = 12, scale = 0)
    private BigDecimal cashReceived;

    @Column(name = "change_given", precision = 12, scale = 0)
    private BigDecimal changeGiven;

    @Column(name = "cash_refunds")
    private Integer cashRefunds;

    @Column(name = "cash_refunded", precision = 12, scale = 0)
    private BigDecimal cashRefunded;

    @Column(name = "card_sales")
    private Integer cardSales;

    @Column(name = "card_total", precision = 12, scale = 0)
    private BigDecimal cardTotal;

    @Column(name = "transfer_sales")
    private Integer transferSales;

    @Column(name = "transfer_total", precision = 12, scale = 0)
    private BigDecimal transferTotal;

    /** Cash that should be in the drawer: float + cash taken - change - refunds. */
    @Column(name = "expected_cash", precision = 12, scale = 0)
    private BigDecimal expectedCash;

    @Column(length = 255)
    private String note;

    public boolean isOpen() {
        return closedAt == null;
    }

    /** Counted minus expected: positive means extra cash, negative means the drawer is short. */
    public BigDecimal getDifference() {
        return countedCash == null || expectedCash == null ? null : countedCash.subtract(expectedCash);
    }
}
