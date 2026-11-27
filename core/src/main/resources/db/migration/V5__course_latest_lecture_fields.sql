ALTER TABLE course
    ADD COLUMN title_en          VARCHAR(256) NULL AFTER title,
    ADD COLUMN instructor_en     VARCHAR(128) NULL AFTER title_en,
    ADD COLUMN department        VARCHAR(128) NULL AFTER instructor_en,
    ADD COLUMN department_en     VARCHAR(256) NULL AFTER department,
    ADD COLUMN academic_year     VARCHAR(32)  NULL AFTER department_en,
    ADD COLUMN academic_year_en  VARCHAR(64)  NULL AFTER academic_year,
    ADD COLUMN category          VARCHAR(64)  NULL AFTER academic_year_en,
    ADD COLUMN category_en       VARCHAR(128) NULL AFTER category,
    ADD COLUMN category_pre2025  VARCHAR(64)  NULL AFTER category_en,
    ADD COLUMN classification    VARCHAR(16)  NULL AFTER category_pre2025,
    ADD COLUMN classification_en VARCHAR(64)  NULL AFTER classification,
    ADD COLUMN credit            INT          NULL AFTER classification_en;

UPDATE course c
    JOIN lecture l ON l.id = c.latest_lecture_id
SET c.title_en          = l.course_title_en,
    c.instructor_en     = l.instructor_en,
    c.department        = l.department,
    c.department_en     = l.department_en,
    c.academic_year     = l.academic_year,
    c.academic_year_en  = l.academic_year_en,
    c.category          = l.category,
    c.category_en       = l.category_en,
    c.category_pre2025  = l.category_pre2025,
    c.classification    = l.classification,
    c.classification_en = l.classification_en,
    c.credit            = l.credit;
