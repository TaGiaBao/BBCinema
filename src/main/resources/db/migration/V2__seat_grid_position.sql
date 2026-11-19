-- V2: grid position of each seat, set by the admin layout editor (drag and drop).
-- Existing seats keep NULL; the application then lays them out by row letter and number.
ALTER TABLE seats
    ADD COLUMN grid_x integer NULL,
    ADD COLUMN grid_y integer NULL;
