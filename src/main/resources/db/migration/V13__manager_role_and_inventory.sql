-- V13: counter managers (MANAGER) belong to a cinema; snack-bar stock with goods-received notes approved by the
-- general manager (ADMIN).
ALTER TABLE users
    MODIFY COLUMN role enum ('ADMIN','MANAGER','STAFF','USER') NOT NULL,
    ADD COLUMN cinema_id bigint NULL,
    ADD CONSTRAINT fk_users_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas (id);

create table inventory_items (
    id bigint not null auto_increment,
    name varchar(100) not null,
    unit varchar(20) not null,
    active bit not null,
    primary key (id),
    constraint uk_inventory_items_name unique (name)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

create table stock_requests (
    id bigint not null auto_increment,
    cinema_id bigint not null,
    status enum ('APPROVED','PENDING','REJECTED') not null,
    requested_by_id bigint not null,
    requested_at datetime(6) not null,
    note varchar(255),
    reviewed_by_id bigint,
    reviewed_at datetime(6),
    review_note varchar(255),
    primary key (id),
    constraint fk_stock_requests_cinema foreign key (cinema_id) references cinemas (id),
    constraint fk_stock_requests_requested_by foreign key (requested_by_id) references users (id),
    constraint fk_stock_requests_reviewed_by foreign key (reviewed_by_id) references users (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

create table stock_request_lines (
    id bigint not null auto_increment,
    request_id bigint not null,
    item_id bigint not null,
    quantity integer not null,
    primary key (id),
    constraint fk_stock_lines_request foreign key (request_id) references stock_requests (id),
    constraint fk_stock_lines_item foreign key (item_id) references inventory_items (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
