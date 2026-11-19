-- V6: membership tier of the member when the booking was made, and its discount.
ALTER TABLE bookings
    ADD COLUMN member_tier varchar(10) NULL,
    ADD COLUMN tier_discount decimal(12,0) NOT NULL DEFAULT 0;
