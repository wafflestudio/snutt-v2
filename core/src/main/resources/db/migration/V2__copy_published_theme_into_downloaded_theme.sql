UPDATE timetable_theme t
JOIN published_theme p ON p.id = t.publication_id
SET t.name = p.name,
    t.colors = p.colors;

ALTER TABLE timetable_theme
    DROP CHECK ck_timetable_theme_content,
    DROP CHECK ck_timetable_theme_palette;

ALTER TABLE timetable_theme
    MODIFY name VARCHAR(128) NOT NULL,
    MODIFY colors JSON NOT NULL;

ALTER TABLE timetable_theme
    ADD CONSTRAINT ck_timetable_theme_palette CHECK (JSON_TYPE(colors) = 'ARRAY' AND JSON_LENGTH(colors) BETWEEN 1 AND 9);
