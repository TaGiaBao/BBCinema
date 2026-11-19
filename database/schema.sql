-- BBCinema database schema for MySQL 8
-- Generated from the JPA entities. The application also creates/updates these tables itself
-- (spring.jpa.hibernate.ddl-auto=update), so running this file is optional.
--
-- Usage:  mysql -u root -p < database/schema.sql

CREATE DATABASE IF NOT EXISTS bbcinema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bbcinema;
SET NAMES utf8mb4;

    create table banners (
        active bit not null,
        sort_order integer not null,
        id bigint not null auto_increment,
        title varchar(150) not null,
        image_path varchar(255) not null,
        link varchar(255),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table booking_combos (
        quantity integer not null,
        unit_price decimal(12,0) not null,
        booking_id bigint not null,
        combo_id bigint not null,
        id bigint not null auto_increment,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table bookings (
        combo_amount decimal(12,0) not null,
        points_earned integer not null,
        points_used integer not null,
        promotion_discount decimal(12,0) not null,
        ticket_amount decimal(12,0) not null,
        total_amount decimal(12,0) not null,
        cancelled_at datetime(6),
        checked_in_at datetime(6),
        created_at datetime(6) not null,
        expires_at datetime(6),
        id bigint not null auto_increment,
        paid_at datetime(6),
        promotion_id bigint,
        showtime_id bigint not null,
        sold_by_id bigint,
        user_id bigint,
        version bigint,
        code varchar(12) not null,
        customer_phone varchar(20),
        customer_name varchar(100),
        customer_email varchar(120),
        channel enum ('COUNTER','ONLINE') not null,
        status enum ('CANCELLED','EXPIRED','PAID','PENDING') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table cinemas (
        active bit not null,
        latitude float(53),
        longitude float(53),
        id bigint not null auto_increment,
        phone varchar(20),
        name varchar(100) not null,
        address varchar(255) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table combos (
        active bit not null,
        price decimal(12,0) not null,
        id bigint not null auto_increment,
        name varchar(100) not null,
        description varchar(255),
        image_path varchar(255),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table genres (
        id bigint not null auto_increment,
        name varchar(60) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table movie_genres (
        genre_id bigint not null,
        movie_id bigint not null,
        primary key (genre_id, movie_id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table movies (
        duration_minutes integer not null,
        release_date date,
        id bigint not null auto_increment,
        language varchar(60),
        director varchar(120),
        title varchar(150) not null,
        slug varchar(170) not null,
        description varchar(2000),
        actors varchar(255),
        poster_path varchar(255),
        trailer_url varchar(255),
        age_rating enum ('K','P','T13','T16','T18') not null,
        status enum ('COMING_SOON','ENDED','NOW_SHOWING') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table payments (
        amount decimal(12,0) not null,
        booking_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        paid_at datetime(6),
        gateway_transaction_no varchar(60),
        transaction_ref varchar(60),
        method enum ('CASH','MOCK','VNPAY') not null,
        status enum ('FAILED','PENDING','REFUNDED','SUCCESS') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table point_transactions (
        points integer not null,
        booking_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint not null,
        note varchar(255),
        type enum ('EARN','REDEEM','REVERT') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table posts (
        published bit not null,
        id bigint not null auto_increment,
        published_at datetime(6) not null,
        title varchar(200) not null,
        slug varchar(220) not null,
        summary varchar(500),
        thumbnail_path varchar(255),
        content text not null,
        type enum ('NEWS','PROMOTION') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table promotions (
        active bit not null,
        discount_percent integer not null,
        max_discount decimal(12,0),
        min_order decimal(12,0),
        usage_limit integer,
        used_count integer not null,
        valid_from date not null,
        valid_to date not null,
        id bigint not null auto_increment,
        code varchar(30) not null,
        description varchar(255),
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table rooms (
        active bit not null,
        row_count integer not null,
        seats_per_row integer not null,
        cinema_id bigint not null,
        id bigint not null auto_increment,
        name varchar(50) not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table seats (
        active bit not null,
        grid_x integer,
        grid_y integer,
        number integer not null,
        row_label varchar(2) not null,
        id bigint not null auto_increment,
        room_id bigint not null,
        seat_type enum ('COUPLE','STANDARD','VIP') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table showtime_seats (
        price decimal(12,0) not null,
        booking_id bigint,
        hold_expires_at datetime(6),
        id bigint not null auto_increment,
        seat_id bigint not null,
        showtime_id bigint not null,
        version bigint,
        status enum ('AVAILABLE','BOOKED','HELD') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table showtimes (
        language varchar(12),
        cancelled bit not null,
        special bit not null,
        end_time datetime(6) not null,
        id bigint not null auto_increment,
        movie_id bigint not null,
        room_id bigint not null,
        start_time datetime(6) not null,
        format enum ('THREE_D','TWO_D') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table ticket_prices (
        price decimal(12,0) not null,
        id bigint not null auto_increment,
        day_type enum ('WEEKDAY','WEEKEND') not null,
        format enum ('THREE_D','TWO_D') not null,
        seat_type enum ('COUPLE','STANDARD','VIP') not null,
        time_slot enum ('BEFORE_17','FROM_17') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create table users (
        birth_date date,
        enabled bit not null,
        points integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        phone varchar(20),
        full_name varchar(100) not null,
        email varchar(120) not null,
        password_hash varchar(255) not null,
        role enum ('ADMIN','STAFF','USER') not null,
        primary key (id)
    ) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

    create index idx_booking_status_expiry 
       on bookings (status, expires_at);

    create index idx_booking_user 
       on bookings (user_id);

    alter table bookings 
       add constraint UKdbhy1hjapco4w66anfuxdt55r unique (code);

    alter table genres 
       add constraint UKpe1a9woik1k97l87cieguyhh4 unique (name);

    alter table movies 
       add constraint UK3xo75bcan34s1o1fldt94skss unique (slug);

    alter table posts 
       add constraint UKqmmso8qxjpbxwegdtp0l90390 unique (slug);

    alter table promotions 
       add constraint UKjdho73ymbyu46p2hh562dk4kk unique (code);

    alter table seats 
       add constraint UKmhj60dkp0cee4l0jvrk7k56hu unique (room_id, row_label, number);

    create index idx_ss_status_expiry 
       on showtime_seats (status, hold_expires_at);

    alter table showtime_seats 
       add constraint UK67piko60j6b7uubu1u84ma1tx unique (showtime_id, seat_id);

    create index idx_showtime_start 
       on showtimes (start_time);

    alter table ticket_prices 
       add constraint UKti6ojrlvlrgo4egs2h51du8qo unique (seat_type, format, day_type, time_slot);

    alter table users 
       add constraint UK6dotkott2kjsp8vw4d0m25fb7 unique (email);

    alter table booking_combos 
       add constraint FKibtryce575stcoiwhwh15l6jf 
       foreign key (booking_id) 
       references bookings (id);

    alter table booking_combos 
       add constraint FKnxrwkgbu2w9vw3kxqp2ovurcn 
       foreign key (combo_id) 
       references combos (id);

    alter table bookings 
       add constraint FKk4byobgkjv3y3952wwpxyep7o 
       foreign key (promotion_id) 
       references promotions (id);

    alter table bookings 
       add constraint FKc7q4u7vleq90vlvy8c7lmwtyl 
       foreign key (showtime_id) 
       references showtimes (id);

    alter table bookings 
       add constraint FKcmluruka4qjcnqv8rie0eftk8 
       foreign key (sold_by_id) 
       references users (id);

    alter table bookings 
       add constraint FKeyog2oic85xg7hsu2je2lx3s6 
       foreign key (user_id) 
       references users (id);

    alter table movie_genres 
       add constraint FKnfpjuak0xiqpca3gjkwrixiig 
       foreign key (genre_id) 
       references genres (id);

    alter table movie_genres 
       add constraint FK4ak9svw913jblkfgru84h2phd 
       foreign key (movie_id) 
       references movies (id);

    alter table payments 
       add constraint FKc52o2b1jkxttngufqp3t7jr3h 
       foreign key (booking_id) 
       references bookings (id);

    alter table point_transactions 
       add constraint FKg9eq16mockkurgugypdgu1x63 
       foreign key (booking_id) 
       references bookings (id);

    alter table point_transactions 
       add constraint FKcqbvxpskb8p7mko50hokltu5b 
       foreign key (user_id) 
       references users (id);

    alter table rooms 
       add constraint FKjp9bjtvlojbw581bpq23cpw4j 
       foreign key (cinema_id) 
       references cinemas (id);

    alter table seats 
       add constraint FKg993pi7ucgy616icmddq8u335 
       foreign key (room_id) 
       references rooms (id);

    alter table showtime_seats 
       add constraint FKr4nqcxsnwo9b54ams09gqhiov 
       foreign key (booking_id) 
       references bookings (id);

    alter table showtime_seats 
       add constraint FK89iq58er7mpv0dubsii5o48na 
       foreign key (seat_id) 
       references seats (id);

    alter table showtime_seats 
       add constraint FKdcy3vgv0vf44n12fq5vouan3b 
       foreign key (showtime_id) 
       references showtimes (id);

    alter table showtimes 
       add constraint FKeltpyuei1d5g3n6ikpsjwwil6 
       foreign key (movie_id) 
       references movies (id);

    alter table showtimes 
       add constraint FKrumrrbei9jppryk4teoyoetit 
       foreign key (room_id) 
       references rooms (id);
