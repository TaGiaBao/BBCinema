package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One item and quantity of a goods-received note. */
@Entity
@Table(name = "stock_request_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockRequestLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private StockRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private InventoryItem item;

    /** Decimals allowed, e.g. 12.5 kg of corn. */
    @Column(nullable = false, precision = 12, scale = 3)
    private java.math.BigDecimal quantity;
}
