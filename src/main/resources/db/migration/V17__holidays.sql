-- V17: public holidays and a third price column for them (ticket_prices.day_type = 'HOLIDAY').
-- One change per statement, so the script also runs on H2.
ALTER TABLE ticket_prices MODIFY COLUMN day_type enum ('HOLIDAY','WEEKDAY','WEEKEND') NOT NULL;
CREATE TABLE holidays (
    id bigint NOT NULL AUTO_INCREMENT,
    holiday_date date NOT NULL,
    name varchar(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_holidays_date UNIQUE (holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
