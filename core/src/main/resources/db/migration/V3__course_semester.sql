CREATE TABLE course_semester (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    year INT NOT NULL,
    semester INT NOT NULL,
    credit INT NOT NULL,
    academic_year VARCHAR(255) NULL,
    category VARCHAR(255) NULL,
    classification VARCHAR(255) NULL,
    extra_info LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_course_semester UNIQUE (course_id, year, semester),
    CONSTRAINT fk_course_semester_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE
);

INSERT INTO course_semester
    (course_id, year, semester, credit, academic_year, category, classification, extra_info, created_at, updated_at)
SELECT l.course_id, l.year, l.semester, l.credit, l.academic_year, l.category, l.classification,
       l.remark, l.created_at, l.updated_at
FROM lecture l
JOIN (
    SELECT MIN(id) AS id FROM lecture WHERE course_id IS NOT NULL GROUP BY course_id, year, semester
) first_offering ON first_offering.id = l.id;
