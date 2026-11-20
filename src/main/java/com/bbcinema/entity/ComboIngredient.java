package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** What one portion of a combo takes from the stock, e.g. Combo Solo = 0.15 kg corn + 1 cup + 1 can. */
@Entity
@Table(name = "combo_ingredients", uniqueConstraints = @UniqueConstraint(columnNames = {"combo_id", "item_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ComboIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "combo_id")
    private Combo combo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private InventoryItem item;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity;
}
