-- V9: members confirm their e-mail address before signing in. Existing accounts count as confirmed.
ALTER TABLE users
    ADD COLUMN email_verified_at datetime(6) NULL,
    ADD COLUMN verify_token_hash varchar(64) NULL,
    ADD COLUMN verify_token_expires_at datetime(6) NULL;
UPDATE users SET email_verified_at = created_at;
