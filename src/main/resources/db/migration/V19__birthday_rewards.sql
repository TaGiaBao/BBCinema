-- V19: birthday reward. The general manager sets the gift once (birthday_reward_settings, a single row with id 1);
-- each eligible member then gets one personal, single-use promotion code per year, valid in their birthday month.
-- One statement per change, so the script also runs on H2.

-- A promotion with an owner can only be used by that member's account (personal codes such as birthday gifts).
ALTER TABLE promotions ADD COLUMN owner_id bigint NULL;
ALTER TABLE promotions ADD CONSTRAINT fk_promotions_owner FOREIGN KEY (owner_id) REFERENCES users (id);
-- A fixed discount in VND; when set it replaces discount_percent (which then stays 0).
ALTER TABLE promotions ADD COLUMN discount_amount decimal(12,0) NULL;

create table birthday_reward_settings (
    id bigint not null,
    enabled bit not null,
    discount_type varchar(10) not null,
    discount_value decimal(12,0) not null,
    max_discount decimal(12,0),
    min_order decimal(12,0),
    updated_at datetime(6),
    primary key (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Off until the general manager turns it on, so no member is mailed by surprise after an upgrade.
INSERT INTO birthday_reward_settings (id, enabled, discount_type, discount_value, max_discount, min_order)
VALUES (1, false, 'PERCENT', 20, 50000, NULL);

-- One gift per member per year: the unique key stops a second code even if two app instances run the job at once
-- or the member changes their birth date later in the year.
create table birthday_rewards (
    id bigint not null auto_increment,
    user_id bigint not null,
    reward_year integer not null,
    promotion_id bigint not null,
    issued_at datetime(6) not null,
    primary key (id),
    constraint uk_birthday_rewards_user_year unique (user_id, reward_year),
    constraint uk_birthday_rewards_promotion unique (promotion_id),
    constraint fk_birthday_rewards_user foreign key (user_id) references users (id),
    constraint fk_birthday_rewards_promotion foreign key (promotion_id) references promotions (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
