-- V11: record when a payment was refunded and which staff member handed the money back.
ALTER TABLE payments
    ADD COLUMN refunded_at datetime(6) NULL,
    ADD COLUMN refunded_by_id bigint NULL,
    ADD CONSTRAINT fk_payments_refunded_by FOREIGN KEY (refunded_by_id) REFERENCES users (id);
