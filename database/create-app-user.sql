-- Database accounts for running BBCinema in production (MySQL 8). Run once as root:
--   mysql -u root -p < database/create-app-user.sql
-- Replace both passwords first. Then start the app with
--   DB_USERNAME=bbcinema_app        DB_PASSWORD=<app password>
--   FLYWAY_USER=bbcinema_migrate    FLYWAY_PASSWORD=<migration password>

CREATE DATABASE IF NOT EXISTS bbcinema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Used by Flyway at start-up to create and change tables (src/main/resources/db/migration).
CREATE USER IF NOT EXISTS 'bbcinema_migrate'@'%' IDENTIFIED BY 'CHANGE-ME-migration-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON bbcinema.* TO 'bbcinema_migrate'@'%';

-- Used by the running application: it can read and write rows but cannot change or drop tables.
CREATE USER IF NOT EXISTS 'bbcinema_app'@'%' IDENTIFIED BY 'CHANGE-ME-app-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON bbcinema.* TO 'bbcinema_app'@'%';

FLUSH PRIVILEGES;
