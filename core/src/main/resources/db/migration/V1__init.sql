CREATE TABLE `user`
(
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    email                   VARCHAR(255) NULL,
    is_email_verified       BOOLEAN      NOT NULL DEFAULT FALSE,
    nickname                VARCHAR(64)  NOT NULL,
    local_id                VARCHAR(64)  NULL,
    local_pw                VARCHAR(255) NULL,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    is_admin                BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at           DATETIME(6)  NOT NULL,
    notification_checked_at DATETIME(6)  NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    active_nickname         VARCHAR(64) GENERATED ALWAYS AS (IF(active, nickname, NULL)) VIRTUAL,
    active_local_id         VARCHAR(64) GENERATED ALWAYS AS (IF(active, local_id, NULL)) VIRTUAL,
    active_email            VARCHAR(255) GENERATED ALWAYS AS (IF(active AND is_email_verified, LOWER(email), NULL)) VIRTUAL,
    CONSTRAINT uk_user_active_nickname UNIQUE (active_nickname),
    CONSTRAINT uk_user_active_local_id UNIQUE (active_local_id),
    CONSTRAINT uk_user_active_email UNIQUE (active_email),
    INDEX idx_user_email (email)
);

CREATE TABLE user_social_auth
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    provider     VARCHAR(16)  NOT NULL,
    sub          VARCHAR(128) NOT NULL,
    email        VARCHAR(255) NULL,
    display_name VARCHAR(128) NULL,
    transfer_sub VARCHAR(128) NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_user_social_auth UNIQUE (user_id, provider),
    CONSTRAINT uk_user_social_auth_sub UNIQUE (provider, sub),
    CONSTRAINT fk_user_social_auth_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_user_social_auth_transfer_sub (transfer_sub)
);

CREATE TABLE user_device
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    os_type             VARCHAR(16)  NULL,
    os_version          VARCHAR(32)  NULL,
    device_id           VARCHAR(128) NULL,
    device_model        VARCHAR(64)  NULL,
    app_type            VARCHAR(16)  NULL,
    app_version         VARCHAR(32)  NULL,
    fcm_registration_id VARCHAR(512) NOT NULL,
    is_deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    active_fcm_registration_id VARCHAR(512) GENERATED ALWAYS AS (IF(is_deleted, NULL, fcm_registration_id)) VIRTUAL,
    CONSTRAINT uk_user_device_active_fcm_registration_id UNIQUE (active_fcm_registration_id),
    CONSTRAINT fk_user_device_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_user_device_user (user_id, is_deleted),
    INDEX idx_user_device_fcm_registration_id (fcm_registration_id)
);

CREATE TABLE user_session
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id            BIGINT      NOT NULL,
    refresh_token_hash CHAR(64)    NOT NULL,
    user_device_id     BIGINT      NULL,
    expires_at         DATETIME(6) NOT NULL,
    revoked_at         DATETIME(6) NULL,
    last_used_at       DATETIME(6) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    CONSTRAINT uk_user_session_refresh_token_hash UNIQUE (refresh_token_hash),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_session_user_device FOREIGN KEY (user_device_id) REFERENCES user_device (id) ON DELETE SET NULL,
    INDEX idx_user_session_user (user_id)
);

CREATE TABLE push_preference
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    type       VARCHAR(32) NOT NULL,
    is_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_push_preference_user_type UNIQUE (user_id, type),
    CONSTRAINT fk_push_preference_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE TABLE notification
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NULL,
    title       VARCHAR(255) NOT NULL,
    message     TEXT         NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    deeplink    VARCHAR(512) NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_notification_user_created (user_id, created_at DESC, id DESC),
    INDEX idx_notification_created (created_at DESC, id DESC)
);

CREATE TABLE course
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_number  VARCHAR(32)  NOT NULL,
    instructor     VARCHAR(128) NOT NULL,
    title          VARCHAR(256) NOT NULL,
    department     VARCHAR(128) NULL,
    credit         INT          NULL,
    academic_year  VARCHAR(32)  NULL,
    category       VARCHAR(64)  NULL,
    classification VARCHAR(16)  NULL,
    eval_count     BIGINT       NOT NULL DEFAULT 0,
    avg_rating     DOUBLE       NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    CONSTRAINT uk_course_number_instructor UNIQUE (course_number, instructor),
    INDEX idx_course_avg_rating (avg_rating DESC),
    INDEX idx_course_eval_count (eval_count DESC, id ASC)
);

CREATE TABLE lecture
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id          BIGINT       NULL,
    year               INT          NOT NULL,
    semester           TINYINT      NOT NULL,
    course_number      VARCHAR(32)  NOT NULL,
    lecture_number     VARCHAR(16)  NOT NULL,
    course_title       VARCHAR(256) NOT NULL,
    instructor         VARCHAR(128) NULL,
    department         VARCHAR(128) NULL,
    academic_year      VARCHAR(32)  NULL,
    category           VARCHAR(64)  NULL,
    category_pre2025   VARCHAR(64)  NULL,
    classification     VARCHAR(16)  NULL,
    course_title_en    VARCHAR(256) NULL,
    instructor_en      VARCHAR(128) NULL,
    department_en      VARCHAR(256) NULL,
    academic_year_en   VARCHAR(64)  NULL,
    category_en        VARCHAR(128) NULL,
    classification_en  VARCHAR(64)  NULL,
    remark_en          TEXT         NULL,
    credit             INT          NOT NULL DEFAULT 0,
    quota              INT          NOT NULL DEFAULT 0,
    freshman_quota     INT          NULL,
    remark             TEXT         NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    CONSTRAINT uk_lecture_offering UNIQUE (year, semester, course_number, lecture_number),
    CONSTRAINT fk_lecture_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE SET NULL,
    INDEX idx_lecture_year_semester (year, semester, id ASC),
    INDEX idx_lecture_course (course_id, year, semester),
    INDEX idx_lecture_course_lecture_number (course_number, lecture_number),
    INDEX idx_lecture_department (department),
    INDEX idx_lecture_classification (classification),
    INDEX idx_lecture_academic_year (academic_year),
    INDEX idx_lecture_category (category),
    INDEX idx_lecture_category_pre2025 (category_pre2025),
    INDEX idx_lecture_credit (credit)
);

CREATE TABLE lecture_registration_status
(
    lecture_id         BIGINT PRIMARY KEY,
    registration_count INT         NOT NULL DEFAULT 0,
    was_full           BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at         DATETIME(6) NOT NULL,
    CONSTRAINT fk_lecture_registration_status_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE CASCADE
);

CREATE TABLE lecture_class_time
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    lecture_id   BIGINT       NOT NULL,
    day          TINYINT      NOT NULL,
    place        VARCHAR(128) NULL,
    start_minute SMALLINT     NOT NULL,
    end_minute   SMALLINT     NOT NULL,
    CONSTRAINT fk_lecture_class_time_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE CASCADE,
    INDEX idx_lecture_class_time_lecture (lecture_id),
    INDEX idx_lecture_class_time_slot (day, start_minute, end_minute)
);

CREATE TABLE coursebook
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    year        INT         NOT NULL,
    semester    TINYINT     NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_coursebook_year_semester UNIQUE (year, semester)
);

CREATE TABLE evaluation
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id          BIGINT      NOT NULL,
    user_id            BIGINT      NULL,
    year               INT         NOT NULL,
    semester           TINYINT     NOT NULL,
    content            TEXT        NOT NULL,
    grade_satisfaction DOUBLE      NULL,
    teaching_skill     DOUBLE      NULL,
    gains              DOUBLE      NULL,
    life_balance       DOUBLE      NULL,
    rating             DOUBLE      NOT NULL,
    like_count         BIGINT      NOT NULL DEFAULT 0,
    is_hidden          BOOLEAN     NOT NULL DEFAULT FALSE,
    is_reported        BOOLEAN     NOT NULL DEFAULT FALSE,
    from_snuev         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    active_user_id     BIGINT GENERATED ALWAYS AS (IF(is_hidden, NULL, user_id)) VIRTUAL,
    CONSTRAINT fk_evaluation_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_evaluation_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE SET NULL,
    CONSTRAINT uk_evaluation_author UNIQUE (course_id, year, semester, active_user_id),
    INDEX idx_evaluation_course_semester (course_id, year, semester, id DESC),
    INDEX idx_evaluation_course_visible (course_id, is_hidden),
    INDEX idx_evaluation_user (user_id, id DESC)
);

CREATE TABLE evaluation_like
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    evaluation_id BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    CONSTRAINT uk_evaluation_like UNIQUE (evaluation_id, user_id),
    CONSTRAINT fk_evaluation_like_evaluation FOREIGN KEY (evaluation_id) REFERENCES evaluation (id) ON DELETE CASCADE,
    CONSTRAINT fk_evaluation_like_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE TABLE evaluation_report
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    evaluation_id BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    content       TEXT        NOT NULL,
    is_hidden     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    CONSTRAINT uk_evaluation_report UNIQUE (evaluation_id, user_id),
    CONSTRAINT fk_evaluation_report_evaluation FOREIGN KEY (evaluation_id) REFERENCES evaluation (id) ON DELETE CASCADE,
    CONSTRAINT fk_evaluation_report_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE TABLE theme
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NULL,
    builtin_type     INT          NULL,
    name             VARCHAR(128) NOT NULL,
    colors       JSON         NOT NULL,
    origin_theme_id  BIGINT       NULL,
    origin_author_id BIGINT       NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uk_theme_builtin_type UNIQUE (builtin_type),
    CONSTRAINT fk_theme_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_theme_origin_theme FOREIGN KEY (origin_theme_id) REFERENCES theme (id) ON DELETE SET NULL,
    CONSTRAINT fk_theme_origin_author FOREIGN KEY (origin_author_id) REFERENCES `user` (id) ON DELETE SET NULL,
    INDEX idx_theme_user_updated (user_id, updated_at DESC)
);

CREATE TABLE user_preference
(
    user_id          BIGINT NOT NULL PRIMARY KEY,
    default_theme_id BIGINT NOT NULL,
    CONSTRAINT fk_user_preference_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_preference_theme FOREIGN KEY (default_theme_id) REFERENCES theme (id) ON DELETE RESTRICT
);

INSERT INTO theme (id, user_id, builtin_type, name, colors, created_at, updated_at) VALUES
    (1, NULL, 0, 'SNUTT',
     '[{"backgroundColor":"#E54459","foregroundColor":"#ffffff"},{"backgroundColor":"#F58D3D","foregroundColor":"#ffffff"},{"backgroundColor":"#FAC42D","foregroundColor":"#ffffff"},{"backgroundColor":"#A6D930","foregroundColor":"#ffffff"},{"backgroundColor":"#2BC267","foregroundColor":"#ffffff"},{"backgroundColor":"#1BD0C8","foregroundColor":"#ffffff"},{"backgroundColor":"#1D99E8","foregroundColor":"#ffffff"},{"backgroundColor":"#4F48C4","foregroundColor":"#ffffff"},{"backgroundColor":"#AF56B3","foregroundColor":"#ffffff"}]',
     NOW(6), NOW(6)),
    (2, NULL, 1, '가을',
     '[{"backgroundColor":"#B82E31","foregroundColor":"#ffffff"},{"backgroundColor":"#DB701C","foregroundColor":"#ffffff"},{"backgroundColor":"#EAA32A","foregroundColor":"#ffffff"},{"backgroundColor":"#C6C013","foregroundColor":"#ffffff"},{"backgroundColor":"#3A856E","foregroundColor":"#ffffff"},{"backgroundColor":"#19B2AC","foregroundColor":"#ffffff"},{"backgroundColor":"#3994CE","foregroundColor":"#ffffff"},{"backgroundColor":"#3F3A9C","foregroundColor":"#ffffff"},{"backgroundColor":"#924396","foregroundColor":"#ffffff"}]',
     NOW(6), NOW(6)),
    (3, NULL, 2, '모던',
     '[{"backgroundColor":"#F0652A","foregroundColor":"#ffffff"},{"backgroundColor":"#F5AD3E","foregroundColor":"#ffffff"},{"backgroundColor":"#998F36","foregroundColor":"#ffffff"},{"backgroundColor":"#89C291","foregroundColor":"#ffffff"},{"backgroundColor":"#266F55","foregroundColor":"#ffffff"},{"backgroundColor":"#13808F","foregroundColor":"#ffffff"},{"backgroundColor":"#366689","foregroundColor":"#ffffff"},{"backgroundColor":"#432920","foregroundColor":"#ffffff"},{"backgroundColor":"#D82F3D","foregroundColor":"#ffffff"}]',
     NOW(6), NOW(6)),
    (4, NULL, 3, '벚꽃',
     '[{"backgroundColor":"#FD79A8","foregroundColor":"#ffffff"},{"backgroundColor":"#FEC9DD","foregroundColor":"#ffffff"},{"backgroundColor":"#FEB0CC","foregroundColor":"#ffffff"},{"backgroundColor":"#FE93BF","foregroundColor":"#ffffff"},{"backgroundColor":"#E9B1D0","foregroundColor":"#ffffff"},{"backgroundColor":"#C67D97","foregroundColor":"#ffffff"},{"backgroundColor":"#BB8EA7","foregroundColor":"#ffffff"},{"backgroundColor":"#BDB4BF","foregroundColor":"#ffffff"},{"backgroundColor":"#E16597","foregroundColor":"#ffffff"}]',
     NOW(6), NOW(6)),
    (5, NULL, 4, '얼음',
     '[{"backgroundColor":"#AABDCF","foregroundColor":"#ffffff"},{"backgroundColor":"#C0E9E8","foregroundColor":"#ffffff"},{"backgroundColor":"#66B6CA","foregroundColor":"#ffffff"},{"backgroundColor":"#015F95","foregroundColor":"#ffffff"},{"backgroundColor":"#A8D0DB","foregroundColor":"#ffffff"},{"backgroundColor":"#66B6CA","foregroundColor":"#ffffff"},{"backgroundColor":"#62A9D1","foregroundColor":"#ffffff"},{"backgroundColor":"#20363D","foregroundColor":"#ffffff"},{"backgroundColor":"#6D8A96","foregroundColor":"#ffffff"}]',
     NOW(6), NOW(6)),
    (6, NULL, 5, '잔디',
     '[{"backgroundColor":"#4FBEAA","foregroundColor":"#ffffff"},{"backgroundColor":"#9FC1A4","foregroundColor":"#ffffff"},{"backgroundColor":"#5A8173","foregroundColor":"#ffffff"},{"backgroundColor":"#84AEB1","foregroundColor":"#ffffff"},{"backgroundColor":"#266F55","foregroundColor":"#ffffff"},{"backgroundColor":"#D0E0C4","foregroundColor":"#ffffff"},{"backgroundColor":"#59886D","foregroundColor":"#ffffff"},{"backgroundColor":"#476060","foregroundColor":"#ffffff"},{"backgroundColor":"#3D7068","foregroundColor":"#ffffff"}]',
     NOW(6), NOW(6));

CREATE TABLE published_theme
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    theme_id         BIGINT       NOT NULL,
    publish_name     VARCHAR(128) NOT NULL,
    author_anonymous BOOLEAN      NOT NULL DEFAULT FALSE,
    download_count   BIGINT       NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uk_published_theme_theme UNIQUE (theme_id),
    CONSTRAINT fk_published_theme_theme FOREIGN KEY (theme_id) REFERENCES theme (id) ON DELETE CASCADE,
    INDEX idx_published_theme_download (download_count DESC, id DESC)
);

CREATE TABLE timetable
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    year        INT          NOT NULL,
    semester    TINYINT      NOT NULL,
    title       VARCHAR(255) NOT NULL,
    theme_id    BIGINT       NOT NULL DEFAULT 1,
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_timetable_title UNIQUE (user_id, year, semester, title),
    CONSTRAINT fk_timetable_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_timetable_theme FOREIGN KEY (theme_id) REFERENCES theme (id) ON DELETE RESTRICT,
    INDEX idx_timetable_user_semester (user_id, year, semester)
);

CREATE TABLE timetable_lecture
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    timetable_id  BIGINT NOT NULL,
    lecture_id    BIGINT NULL,
    color         JSON   NULL,
    color_index   INT    NOT NULL DEFAULT 0,
    overrides     JSON   NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    CONSTRAINT fk_timetable_lecture_timetable FOREIGN KEY (timetable_id) REFERENCES timetable (id) ON DELETE CASCADE,
    CONSTRAINT fk_timetable_lecture_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE RESTRICT,
    INDEX idx_timetable_lecture_timetable (timetable_id),
    INDEX idx_timetable_lecture_lecture (lecture_id)
);

CREATE TABLE timetable_lecture_reminder
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    timetable_lecture_id BIGINT      NOT NULL,
    offset_minutes       INT         NOT NULL,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    CONSTRAINT uk_timetable_lecture_reminder UNIQUE (timetable_lecture_id),
    CONSTRAINT fk_reminder_timetable_lecture FOREIGN KEY (timetable_lecture_id)
        REFERENCES timetable_lecture (id) ON DELETE CASCADE
);

CREATE TABLE timetable_lecture_reminder_schedule
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    reminder_id        BIGINT       NOT NULL,
    day                TINYINT      NOT NULL,
    minute             INT          NOT NULL,
    recent_notified_at DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    CONSTRAINT fk_reminder_schedule_reminder FOREIGN KEY (reminder_id)
        REFERENCES timetable_lecture_reminder (id) ON DELETE CASCADE,
    UNIQUE INDEX idx_reminder_schedule_fire (reminder_id, day, minute),
    INDEX idx_reminder_schedule_slot (day, minute)
);

CREATE TABLE bookmark_lecture
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    year       INT         NOT NULL,
    semester   TINYINT     NOT NULL,
    lecture_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_bookmark_lecture UNIQUE (user_id, year, semester, lecture_id),
    CONSTRAINT fk_bookmark_lecture_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_lecture_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE CASCADE,
    INDEX idx_bookmark_lecture_lecture (lecture_id)
);

CREATE TABLE vacancy_notification
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    lecture_id  BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_vacancy_notification UNIQUE (user_id, lecture_id),
    CONSTRAINT fk_vacancy_notification_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_vacancy_notification_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE CASCADE,
    INDEX idx_vacancy_notification_lecture (lecture_id)
);

CREATE TABLE friend
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_user_id      BIGINT      NOT NULL,
    to_user_id        BIGINT      NOT NULL,
    from_display_name VARCHAR(64) NULL,
    to_display_name   VARCHAR(64) NULL,
    is_accepted       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    user_low          BIGINT GENERATED ALWAYS AS (LEAST(from_user_id, to_user_id)) VIRTUAL,
    user_high         BIGINT GENERATED ALWAYS AS (GREATEST(from_user_id, to_user_id)) VIRTUAL,
    CONSTRAINT uk_friend_pair UNIQUE (user_low, user_high),
    CONSTRAINT fk_friend_from_user FOREIGN KEY (from_user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_to_user FOREIGN KEY (to_user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_friend_from_user (from_user_id, is_accepted),
    INDEX idx_friend_to_user (to_user_id, is_accepted)
);

CREATE TABLE popup
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    popup_key        VARCHAR(64)  NOT NULL,
    image_origin_uri VARCHAR(512) NOT NULL,
    link_url         VARCHAR(512) NULL,
    hidden_days      INT          NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uk_popup_key UNIQUE (popup_key)
);

CREATE TABLE client_config
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(64) NOT NULL,
    value               TEXT        NOT NULL,
    min_ios_version     VARCHAR(32) NULL,
    max_ios_version     VARCHAR(32) NULL,
    min_android_version VARCHAR(32) NULL,
    max_android_version VARCHAR(32) NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    INDEX idx_client_config_name (name)
);

CREATE TABLE lecture_building
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_number     VARCHAR(16)  NOT NULL,
    building_name_kor   VARCHAR(64)  NOT NULL,
    building_name_eng   VARCHAR(128) NOT NULL DEFAULT '',
    campus              VARCHAR(16)  NOT NULL,
    location_in_dms     JSON         NULL,
    location_in_decimal JSON         NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT uk_lecture_building UNIQUE (building_number, campus)
);

CREATE TABLE semester_registration_period
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    year                     INT         NOT NULL,
    semester                 TINYINT     NOT NULL,
    registration_period_list JSON        NOT NULL,
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    CONSTRAINT uk_semester_registration_period UNIQUE (year, semester)
);

CREATE TABLE diary_daily_class_type
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64) NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_diary_daily_class_type_name UNIQUE (name)
);

CREATE TABLE diary_question
(
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    question                        VARCHAR(255) NOT NULL,
    short_question                  VARCHAR(255) NOT NULL,
    answer_list                     JSON         NOT NULL,
    short_answer_list               JSON         NOT NULL,
    active                          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                      DATETIME(6)  NOT NULL,
    updated_at                      DATETIME(6)  NOT NULL
);

CREATE TABLE diary_question_target
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id         BIGINT NOT NULL,
    daily_class_type_id BIGINT NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    CONSTRAINT fk_diary_question_target_question FOREIGN KEY (question_id) REFERENCES diary_question (id) ON DELETE CASCADE,
    CONSTRAINT fk_diary_question_target_type FOREIGN KEY (daily_class_type_id) REFERENCES diary_daily_class_type (id) ON DELETE CASCADE,
    INDEX idx_diary_question_target_type (daily_class_type_id)
);

CREATE TABLE diary_submission
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                  BIGINT       NOT NULL,
    year                     INT          NOT NULL,
    semester                 TINYINT      NOT NULL,
    lecture_id               BIGINT       NULL,
    course_title             VARCHAR(256) NOT NULL,
    comment                  TEXT         NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    CONSTRAINT fk_diary_submission_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_diary_submission_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE SET NULL,
    INDEX idx_diary_submission_user (user_id, year, semester, created_at DESC)
);

CREATE TABLE diary_submission_daily_class_type
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id       BIGINT NOT NULL,
    daily_class_type_id BIGINT NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    CONSTRAINT fk_diary_submission_dct_submission FOREIGN KEY (submission_id) REFERENCES diary_submission (id) ON DELETE CASCADE,
    INDEX idx_diary_submission_dct_submission (submission_id)
);

CREATE TABLE diary_submission_answer
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    question_id   BIGINT NOT NULL,
    answer_index  INT    NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    CONSTRAINT fk_diary_submission_answer_submission FOREIGN KEY (submission_id) REFERENCES diary_submission (id) ON DELETE CASCADE,
    INDEX idx_diary_submission_answer_submission (submission_id)
);