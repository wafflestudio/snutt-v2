DELETE b
FROM bookmark_lecture b
JOIN bookmark_lecture d ON d.user_id = b.user_id AND d.lecture_id = b.lecture_id AND d.id < b.id;

ALTER TABLE bookmark_lecture
    ADD CONSTRAINT uk_bookmark_lecture_user_lecture UNIQUE (user_id, lecture_id);

ALTER TABLE bookmark_lecture
    DROP INDEX uk_bookmark_lecture,
    DROP COLUMN year,
    DROP COLUMN semester;

ALTER TABLE bookmark_lecture
    RENAME INDEX uk_bookmark_lecture_user_lecture TO uk_bookmark_lecture;
