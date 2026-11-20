package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A stock item of the snack bar (corn, cups, syrup...), kept per cinema. The list is set by the general manager. */
@Entity
@Table(name = "inventory_items")
@Getter
@Setter
@NoArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Units the general manager can pick when adding an item. */
    public static final java.util.List<String> UNITS =
            java.util.List.of("kg", "g", "lít", "chai", "lon", "gói", "cái", "ly", "hộp", "thùng", "bình");

    /** Counting unit shown next to quantities, one of {@link #UNITS}. */
    @Column(nullable = false, length = 20)
    private String unit;

    @Column(nullable = false)
    private boolean active = true;

    public InventoryItem(String name, String unit) {
        this.name = name;
        this.unit = unit;
    }
}
