-- V21: who did what in the internal area (staff, counter managers, the general manager). One row per action;
-- rows are only ever added, never changed. One statement per change, so the script also runs on H2.
CREATE TABLE audit_logs (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6) NOT NULL,
    actor_id bigint NULL,
    actor_email varchar(150) NOT NULL,
    actor_role varchar(10) NULL,
    action varchar(100) NOT NULL,
    target varchar(100) NULL,
    detail varchar(500) NULL,
    success bit NOT NULL,
    http_method varchar(10) NULL,
    path varchar(255) NULL,
    ip varchar(45) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
CREATE INDEX idx_audit_logs_actor_email ON audit_logs (actor_email);
