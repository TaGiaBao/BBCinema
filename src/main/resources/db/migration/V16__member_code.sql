-- V16: a member code (BB + 8 characters) that members show at the counter to get member prices and points.
-- Nullable: staff accounts have none, and existing members get theirs when the application starts.
-- One statement per change, so the script also runs on H2.
ALTER TABLE users ADD COLUMN member_code varchar(12) NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_member_code UNIQUE (member_code);
