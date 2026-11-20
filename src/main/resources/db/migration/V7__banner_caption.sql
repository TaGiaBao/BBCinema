-- V7: banner text is written over the picture by the home page, so it can be translated.
ALTER TABLE banners
    ADD COLUMN subtitle varchar(255) NULL,
    ADD COLUMN title_en varchar(150) NULL,
    ADD COLUMN subtitle_en varchar(255) NULL,
    ADD COLUMN caption_hidden bit NOT NULL DEFAULT 0;
