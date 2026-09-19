ALTER TABLE course
    ADD COLUMN latest_lecture_id BIGINT NULL,
    ADD CONSTRAINT fk_course_latest_lecture FOREIGN KEY (latest_lecture_id) REFERENCES lecture (id) ON DELETE SET NULL;

UPDATE course c
JOIN (
    SELECT course_id, id
    FROM (
        SELECT id,
               course_id,
               ROW_NUMBER() OVER (
                   PARTITION BY course_id
                   ORDER BY year DESC, semester DESC, updated_at DESC, id DESC
               ) AS rn
        FROM lecture
        WHERE course_id IS NOT NULL
    ) ranked
    WHERE rn = 1
) latest ON latest.course_id = c.id
SET c.latest_lecture_id = latest.id;

ALTER TABLE timetable
    ADD INDEX idx_timetable_semester (year, semester);
