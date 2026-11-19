-- V4: one-time "forgot password" token (SHA-256 hash) and its expiry.
ALTER TABLE users
    ADD COLUMN reset_token_hash varchar(64) NULL,
    ADD COLUMN reset_token_expires_at datetime(6) NULL;
