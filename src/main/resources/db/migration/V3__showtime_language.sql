-- V3: language of a showtime (SUBTITLED, DUBBED, VIETNAMESE). NULL = default for the movie.
ALTER TABLE showtimes ADD COLUMN language varchar(12) NULL;
