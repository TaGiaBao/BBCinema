-- V5: member reviews of movies, and the cached average on each movie.
create table reviews (
    hidden bit not null,
    rating integer not null,
    created_at datetime(6) not null,
    id bigint not null auto_increment,
    movie_id bigint not null,
    updated_at datetime(6) not null,
    user_id bigint not null,
    comment varchar(1000),
    primary key (id),
    constraint UK_reviews_movie_user unique (movie_id, user_id),
    constraint FK_reviews_movie foreign key (movie_id) references movies (id),
    constraint FK_reviews_user foreign key (user_id) references users (id)
) engine=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE movies
    ADD COLUMN rating_average float(53) NULL,
    ADD COLUMN rating_count integer NOT NULL DEFAULT 0;
