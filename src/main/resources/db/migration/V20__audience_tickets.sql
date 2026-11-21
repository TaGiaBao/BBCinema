-- V20: audience tickets (HSSV, children, seniors) sold at the counter. The general manager sets each type's
-- discount on /admin/prices; each sold seat keeps the type and the discount it got, so the printed ticket and
-- later reports show what was charged. One statement per change, so the script also runs on H2.

create table audience_discounts (
    id bigint not null auto_increment,
    audience_type varchar(10) not null,
    enabled bit not null,
    discount_type varchar(10) not null,
    discount_value decimal(12,0) not null,
    on_holidays bit not null,
    updated_at datetime(6),
    primary key (id),
    constraint uk_audience_discounts_type unique (audience_type)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO audience_discounts (audience_type, enabled, discount_type, discount_value, on_holidays) VALUES ('STUDENT', true, 'PERCENT', 20, false);
INSERT INTO audience_discounts (audience_type, enabled, discount_type, discount_value, on_holidays) VALUES ('CHILD', true, 'PERCENT', 30, false);
INSERT INTO audience_discounts (audience_type, enabled, discount_type, discount_value, on_holidays) VALUES ('SENIOR', true, 'PERCENT', 30, false);

-- Per seat: the ticket type and how much it took off the seat price (null for an ordinary ticket).
ALTER TABLE showtime_seats ADD COLUMN audience_type varchar(10) NULL;
ALTER TABLE showtime_seats ADD COLUMN audience_discount decimal(12,0) NULL;
-- Per booking: the sum of the seats' audience discounts.
ALTER TABLE bookings ADD COLUMN audience_discount decimal(12,0) NOT NULL DEFAULT 0;
