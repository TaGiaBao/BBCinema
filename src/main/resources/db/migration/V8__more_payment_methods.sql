-- V8: bank transfer by VietQR, bank card at the counter, MoMo.
ALTER TABLE payments
    MODIFY COLUMN method enum ('BANK_QR','CARD','CASH','MOCK','MOMO','VNPAY') NOT NULL;
