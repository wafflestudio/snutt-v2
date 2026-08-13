-- snutt v2 통합 스키마. 설계 근거는 PLAN.md §2.
-- 테이블·컬럼명 단수. external_id: v1 클라이언트가 저장하는 24-hex 공개 id (이관 행은 Mongo ObjectId).

CREATE TABLE `user`
(
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id             CHAR(24)     NOT NULL,
    email                   VARCHAR(255) NULL,
    is_email_verified       BOOLEAN      NOT NULL DEFAULT FALSE,
    nickname                VARCHAR(64)  NOT NULL,
    local_id                VARCHAR(64)  NULL,
    local_pw                VARCHAR(255) NULL,
    facebook_sub            VARCHAR(128) NULL,
    facebook_name           VARCHAR(128) NULL,
    apple_sub               VARCHAR(128) NULL,
    apple_transfer_sub      VARCHAR(128) NULL,
    apple_email             VARCHAR(255) NULL,
    google_sub              VARCHAR(128) NULL,
    google_email            VARCHAR(255) NULL,
    kakao_sub               VARCHAR(128) NULL,
    kakao_email             VARCHAR(255) NULL,
    credential_hash         VARCHAR(255) NOT NULL,
    fcm_key                 VARCHAR(512) NULL,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    is_admin                BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at           DATETIME(6)  NOT NULL,
    notification_checked_at DATETIME(6)  NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    CONSTRAINT uk_user_external_id UNIQUE (external_id),
    CONSTRAINT uk_user_credential_hash UNIQUE (credential_hash),
    CONSTRAINT uk_user_nickname UNIQUE (nickname),
    -- email/소셜 sub의 유일성은 active 사용자에 한정되므로 (Mongo partial unique index의 계승)
    -- UNIQUE가 아닌 보조 인덱스 + 서비스 계층 검증으로 강제한다. PLAN.md §2 주의 참조.
    INDEX idx_user_email (email),
    INDEX idx_user_local_id (local_id),
    INDEX idx_user_facebook_sub (facebook_sub),
    INDEX idx_user_apple_sub (apple_sub),
    INDEX idx_user_google_sub (google_sub),
    INDEX idx_user_kakao_sub (kakao_sub)
);

CREATE TABLE user_device
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id         CHAR(24)     NOT NULL,
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
    CONSTRAINT uk_user_device_external_id UNIQUE (external_id),
    CONSTRAINT fk_user_device_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_user_device_fcm_registration_id (fcm_registration_id)
);

CREATE TABLE user_session
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id        CHAR(24)    NOT NULL,
    user_id            BIGINT      NOT NULL,
    refresh_token_hash CHAR(64)    NOT NULL,
    user_device_id     BIGINT      NULL,
    expires_at         DATETIME(6) NOT NULL,
    revoked_at         DATETIME(6) NULL,
    last_used_at       DATETIME(6) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    CONSTRAINT uk_user_session_external_id UNIQUE (external_id),
    CONSTRAINT uk_user_session_refresh_token_hash UNIQUE (refresh_token_hash),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_session_user_device FOREIGN KEY (user_device_id) REFERENCES user_device (id) ON DELETE SET NULL
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
    external_id CHAR(24)     NOT NULL,
    -- user_id NULL = 전체 공지 (broadcast)
    user_id     BIGINT       NULL,
    title       VARCHAR(255) NOT NULL,
    message     TEXT         NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    deeplink    VARCHAR(512) NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_notification_external_id UNIQUE (external_id),
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_notification_user_created (user_id, created_at DESC),
    INDEX idx_notification_created (created_at DESC)
);

CREATE TABLE course
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_number VARCHAR(32)  NOT NULL,
    instructor    VARCHAR(128) NOT NULL,
    title         VARCHAR(256) NOT NULL,
    department    VARCHAR(128) NULL,
    credit        INT          NULL,
    academic_year VARCHAR(32)  NULL,
    category      VARCHAR(64)  NULL,
    classification VARCHAR(16) NULL,
    -- 강의평 트랜잭션에서 갱신되는 비정규화 집계 (구 Mongo lectures.evInfo의 대체)
    eval_count    BIGINT       NOT NULL DEFAULT 0,
    avg_rating    DOUBLE       NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uk_course_number_instructor UNIQUE (course_number, instructor),
    INDEX idx_course_avg_rating (avg_rating DESC)
);

CREATE TABLE lecture
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id         CHAR(24)     NOT NULL,
    course_id           BIGINT       NULL,
    year                INT          NOT NULL,
    semester            TINYINT      NOT NULL,
    course_number       VARCHAR(32)  NOT NULL,
    lecture_number      VARCHAR(16)  NOT NULL,
    course_title        VARCHAR(256) NOT NULL,
    instructor          VARCHAR(128) NULL,
    department          VARCHAR(128) NULL,
    academic_year       VARCHAR(32)  NULL,
    category            VARCHAR(64)  NULL,
    category_pre2025    VARCHAR(64)  NULL,
    classification      VARCHAR(16)  NULL,
    credit              INT          NOT NULL DEFAULT 0,
    quota               INT          NOT NULL DEFAULT 0,
    freshman_quota      INT          NULL,
    remark              TEXT         NULL,
    registration_count  INT          NOT NULL DEFAULT 0,
    was_full            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT uk_lecture_external_id UNIQUE (external_id),
    CONSTRAINT uk_lecture_semester_course_lecture_number UNIQUE (year, semester, course_number, lecture_number),
    CONSTRAINT fk_lecture_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE SET NULL,
    INDEX idx_lecture_year_semester (year, semester),
    INDEX idx_lecture_course_lecture_number (course_number, lecture_number)
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
    INDEX idx_lecture_class_time_slot (day, start_minute, end_minute)
);

CREATE TABLE coursebook
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id CHAR(24)    NOT NULL,
    year        INT         NOT NULL,
    semester    TINYINT     NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_coursebook_external_id UNIQUE (external_id),
    CONSTRAINT uk_coursebook_year_semester UNIQUE (year, semester)
);

CREATE TABLE tag_list
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    year           INT         NOT NULL,
    semester       TINYINT     NOT NULL,
    tag_collection JSON        NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT uk_tag_list_year_semester UNIQUE (year, semester)
);

CREATE TABLE tag_group
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    ordering   INT         NOT NULL,
    color      VARCHAR(16) NULL,
    value_type VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_tag_group_name UNIQUE (name)
);

CREATE TABLE tag
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    tag_group_id BIGINT       NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(255) NULL,
    ordering     INT          NOT NULL,
    int_value    INT          NULL,
    string_value VARCHAR(64)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uk_tag_group_ordering UNIQUE (tag_group_id, ordering),
    CONSTRAINT fk_tag_tag_group FOREIGN KEY (tag_group_id) REFERENCES tag_group (id) ON DELETE CASCADE
);

CREATE TABLE evaluation
(
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id          BIGINT      NOT NULL,
    -- NULL = 탈퇴 등으로 소멸한 작성자 (이관 시 고아 행 tombstone)
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
    CONSTRAINT fk_evaluation_course FOREIGN KEY (course_id) REFERENCES course (id) ON DELETE CASCADE,
    CONSTRAINT fk_evaluation_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE SET NULL,
    INDEX idx_evaluation_course_semester (course_id, year, semester, id DESC),
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
    external_id      CHAR(24)     NOT NULL,
    user_id          BIGINT       NOT NULL,
    name             VARCHAR(128) NOT NULL,
    color_list       JSON         NOT NULL,
    -- NULL = 직접 만든 테마, SET = 마켓에서 받아온 테마 (원본 참조)
    origin_theme_id  BIGINT       NULL,
    origin_author_id BIGINT       NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uk_theme_external_id UNIQUE (external_id),
    CONSTRAINT fk_theme_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_theme_origin_theme FOREIGN KEY (origin_theme_id) REFERENCES theme (id) ON DELETE SET NULL,
    CONSTRAINT fk_theme_origin_author FOREIGN KEY (origin_author_id) REFERENCES `user` (id) ON DELETE SET NULL,
    INDEX idx_theme_user (user_id)
);

-- 마켓 공개 정보는 테마 행과 분리한다. 공개 = published_theme 행 존재, 비공개 = 삭제
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
    INDEX idx_published_theme_download (download_count DESC)
);

CREATE TABLE timetable
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id CHAR(24)     NOT NULL,
    user_id     BIGINT       NOT NULL,
    year        INT          NOT NULL,
    semester    TINYINT      NOT NULL,
    title       VARCHAR(255) NOT NULL,
    -- builtin 테마 번호. custom 테마 사용 시 theme_id 참조
    theme       TINYINT      NOT NULL DEFAULT 0,
    theme_id    BIGINT       NULL,
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_timetable_external_id UNIQUE (external_id),
    CONSTRAINT fk_timetable_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_timetable_theme FOREIGN KEY (theme_id) REFERENCES theme (id) ON DELETE SET NULL,
    INDEX idx_timetable_user_semester (user_id, year, semester)
);

CREATE TABLE timetable_lecture
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id         CHAR(24)    NOT NULL,
    timetable_id        BIGINT      NOT NULL,
    -- NULL = 완전 custom 강의 (내용은 아래 override 컬럼이 보유)
    lecture_id          BIGINT      NULL,
    color               JSON        NULL,
    color_index         INT         NOT NULL DEFAULT 0,
    -- lecture 참조 강의의 사용자 수정분 (non-NULL만 덮어쓴다). custom 강의면 내용 자체를 보유한다
    course_title         VARCHAR(256) NULL,
    instructor           VARCHAR(128) NULL,
    credit               INT          NULL,
    remark               TEXT         NULL,
    class_place_and_time JSON         NULL,
    academic_year        VARCHAR(64)  NULL,
    category             VARCHAR(64)  NULL,
    classification       VARCHAR(64)  NULL,
    category_pre2025     VARCHAR(64)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    CONSTRAINT uk_timetable_lecture_external_id UNIQUE (external_id),
    CONSTRAINT fk_timetable_lecture_timetable FOREIGN KEY (timetable_id) REFERENCES timetable (id) ON DELETE CASCADE,
    -- 강의 삭제는 sugang-sync가 custom 전환 처리 후에만 가능 (RESTRICT로 강제)
    CONSTRAINT fk_timetable_lecture_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id),
    INDEX idx_timetable_lecture_lecture (lecture_id)
);

CREATE TABLE timetable_lecture_reminder
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id          CHAR(24)    NOT NULL,
    timetable_lecture_id BIGINT      NOT NULL,
    offset_minutes       INT         NOT NULL,
    schedule_list        JSON        NOT NULL,
    -- schedule_list에서 계산한 다음 발화 시각. 매분 스케줄러가 인덱스로 조회
    next_day             TINYINT     NULL,
    next_minute          SMALLINT    NULL,
    recent_notified_at   DATETIME(6) NULL,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    CONSTRAINT uk_timetable_lecture_reminder_external_id UNIQUE (external_id),
    CONSTRAINT uk_timetable_lecture_reminder UNIQUE (timetable_lecture_id),
    CONSTRAINT fk_reminder_timetable_lecture FOREIGN KEY (timetable_lecture_id)
        REFERENCES timetable_lecture (id) ON DELETE CASCADE,
    INDEX idx_reminder_next_fire (next_day, next_minute)
);

CREATE TABLE bookmark
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id CHAR(24)    NOT NULL,
    user_id     BIGINT      NOT NULL,
    year        INT         NOT NULL,
    semester    TINYINT     NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_bookmark_external_id UNIQUE (external_id),
    CONSTRAINT uk_bookmark_user_semester UNIQUE (user_id, year, semester),
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
);

CREATE TABLE bookmark_lecture
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    bookmark_id BIGINT      NOT NULL,
    lecture_id  BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_bookmark_lecture UNIQUE (bookmark_id, lecture_id),
    CONSTRAINT fk_bookmark_lecture_bookmark FOREIGN KEY (bookmark_id) REFERENCES bookmark (id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_lecture_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE CASCADE,
    INDEX idx_bookmark_lecture_lecture (lecture_id)
);

CREATE TABLE vacancy_notification
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id CHAR(24)    NOT NULL,
    user_id     BIGINT      NOT NULL,
    lecture_id  BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_vacancy_notification_external_id UNIQUE (external_id),
    CONSTRAINT uk_vacancy_notification UNIQUE (user_id, lecture_id),
    CONSTRAINT fk_vacancy_notification_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_vacancy_notification_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE CASCADE,
    INDEX idx_vacancy_notification_lecture (lecture_id)
);

CREATE TABLE friend
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id       CHAR(24)    NOT NULL,
    from_user_id      BIGINT      NOT NULL,
    to_user_id        BIGINT      NOT NULL,
    from_display_name VARCHAR(64) NULL,
    to_display_name   VARCHAR(64) NULL,
    is_accepted       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    CONSTRAINT uk_friend_external_id UNIQUE (external_id),
    CONSTRAINT uk_friend_pair UNIQUE (from_user_id, to_user_id),
    CONSTRAINT fk_friend_from_user FOREIGN KEY (from_user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_to_user FOREIGN KEY (to_user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_friend_to_user (to_user_id)
);

CREATE TABLE popup
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id      CHAR(24)     NOT NULL,
    popup_key        VARCHAR(64)  NOT NULL,
    image_origin_uri VARCHAR(512) NOT NULL,
    link_url         VARCHAR(512) NULL,
    hidden_days      INT          NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uk_popup_external_id UNIQUE (external_id),
    CONSTRAINT uk_popup_key UNIQUE (popup_key)
);

CREATE TABLE client_config
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id         CHAR(24)    NOT NULL,
    name                VARCHAR(64) NOT NULL,
    value               JSON        NOT NULL,
    min_ios_version     VARCHAR(32) NULL,
    max_ios_version     VARCHAR(32) NULL,
    min_android_version VARCHAR(32) NULL,
    max_android_version VARCHAR(32) NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    CONSTRAINT uk_client_config_external_id UNIQUE (external_id),
    INDEX idx_client_config_name (name)
);

CREATE TABLE lecture_building
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id         CHAR(24)     NOT NULL,
    building_number     VARCHAR(16)  NOT NULL,
    building_name_kor   VARCHAR(64)  NOT NULL,
    building_name_eng   VARCHAR(128) NOT NULL DEFAULT '',
    campus              VARCHAR(16)  NOT NULL,
    location_in_dms     JSON         NULL,
    location_in_decimal JSON         NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT uk_lecture_building_external_id UNIQUE (external_id),
    CONSTRAINT uk_lecture_building UNIQUE (building_number, campus)
);

CREATE TABLE semester_registration_period
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id              CHAR(24)    NOT NULL,
    year                     INT         NOT NULL,
    semester                 TINYINT     NOT NULL,
    registration_period_list JSON        NOT NULL,
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    CONSTRAINT uk_semester_registration_period_external_id UNIQUE (external_id),
    CONSTRAINT uk_semester_registration_period UNIQUE (year, semester)
);

CREATE TABLE diary_daily_class_type
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id CHAR(24)    NOT NULL,
    name        VARCHAR(64) NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_diary_daily_class_type_external_id UNIQUE (external_id),
    CONSTRAINT uk_diary_daily_class_type_name UNIQUE (name)
);

CREATE TABLE diary_question
(
    id                             BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id                    CHAR(24)     NOT NULL,
    question                       VARCHAR(255) NOT NULL,
    short_question                 VARCHAR(255) NOT NULL,
    answer_list                    JSON         NOT NULL,
    short_answer_list              JSON         NOT NULL,
    target_daily_class_type_id_list JSON        NOT NULL,
    active                         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                     DATETIME(6)  NOT NULL,
    updated_at                     DATETIME(6)  NOT NULL,
    CONSTRAINT uk_diary_question_external_id UNIQUE (external_id)
);

CREATE TABLE diary_submission
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id              CHAR(24)     NOT NULL,
    user_id                  BIGINT       NOT NULL,
    year                     INT          NOT NULL,
    semester                 TINYINT      NOT NULL,
    lecture_id               BIGINT       NULL,
    course_title             VARCHAR(256) NOT NULL,
    comment                  TEXT         NULL,
    daily_class_type_id_list JSON         NOT NULL,
    question_answer_list     JSON         NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    CONSTRAINT uk_diary_submission_external_id UNIQUE (external_id),
    CONSTRAINT fk_diary_submission_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    CONSTRAINT fk_diary_submission_lecture FOREIGN KEY (lecture_id) REFERENCES lecture (id) ON DELETE SET NULL,
    INDEX idx_diary_submission_user (user_id, year, semester, created_at DESC)
);
