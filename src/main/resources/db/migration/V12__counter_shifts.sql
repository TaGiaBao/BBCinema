-- V12: counter shifts. A manager opens a staff member's shift with the opening float and closes it after
-- counting the drawer. Counter sales belong to the shift they were sold in; cash refunds to the shift that paid them.
create table counter_shifts (
    id bigint not null auto_increment,
    staff_id bigint not null,
    cinema_id bigint,
    opened_at datetime(6) not null,
    opened_by_id bigint not null,
    opening_float decimal(12,0) not null,
    closed_at datetime(6),
    closed_by_id bigint,
    counted_cash decimal(12,0),
    cash_sales integer,
    cash_received decimal(12,0),
    change_given decimal(12,0),
    cash_refunds integer,
    cash_refunded decimal(12,0),
    card_sales integer,
    card_total decimal(12,0),
    transfer_sales integer,
    transfer_total decimal(12,0),
    expected_cash decimal(12,0),
    note varchar(255),
    primary key (id),
    constraint fk_shift_staff foreign key (staff_id) references users (id),
    constraint fk_shift_cinema foreign key (cinema_id) references cinemas (id),
    constraint fk_shift_opened_by foreign key (opened_by_id) references users (id),
    constraint fk_shift_closed_by foreign key (closed_by_id) references users (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE bookings
    ADD COLUMN shift_id bigint NULL,
    ADD CONSTRAINT fk_bookings_shift FOREIGN KEY (shift_id) REFERENCES counter_shifts (id);

ALTER TABLE payments
    ADD COLUMN refund_shift_id bigint NULL,
    ADD CONSTRAINT fk_payments_refund_shift FOREIGN KEY (refund_shift_id) REFERENCES counter_shifts (id);
