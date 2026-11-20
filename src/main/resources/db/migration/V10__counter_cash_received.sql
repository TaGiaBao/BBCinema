-- V10: counter sales record the cash handed over (or the POS slip amount) and the change given back.
ALTER TABLE payments
    ADD COLUMN amount_received decimal(12, 0) NULL,
    ADD COLUMN change_amount decimal(12, 0) NULL;
