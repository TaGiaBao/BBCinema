-- V14: decimal stock quantities (e.g. 0.15 kg of corn), combo recipes, and stock movements from paid / refunded orders.
ALTER TABLE stock_request_lines
    MODIFY COLUMN quantity decimal(12,3) NOT NULL;

create table combo_ingredients (
    id bigint not null auto_increment,
    combo_id bigint not null,
    item_id bigint not null,
    quantity decimal(10,3) not null,
    primary key (id),
    constraint uk_combo_ingredient unique (combo_id, item_id),
    constraint fk_combo_ingredients_combo foreign key (combo_id) references combos (id),
    constraint fk_combo_ingredients_item foreign key (item_id) references inventory_items (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

create table inventory_movements (
    id bigint not null auto_increment,
    cinema_id bigint not null,
    item_id bigint not null,
    quantity decimal(12,3) not null,
    booking_id bigint,
    reason varchar(120) not null,
    created_at datetime(6) not null,
    primary key (id),
    constraint fk_inventory_movements_cinema foreign key (cinema_id) references cinemas (id),
    constraint fk_inventory_movements_item foreign key (item_id) references inventory_items (id),
    constraint fk_inventory_movements_booking foreign key (booking_id) references bookings (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
