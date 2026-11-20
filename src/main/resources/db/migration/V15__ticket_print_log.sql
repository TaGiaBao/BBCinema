-- V15: how many times staff printed a paper ticket for a booking, and when they last did.
-- One column per statement, so the script also runs on H2.
ALTER TABLE bookings ADD COLUMN print_count int NOT NULL DEFAULT 0;
ALTER TABLE bookings ADD COLUMN last_printed_at datetime(6) NULL;
