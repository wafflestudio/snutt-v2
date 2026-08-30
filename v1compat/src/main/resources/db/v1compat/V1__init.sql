CREATE TABLE legacy_access_token
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_legacy_access_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_legacy_access_token_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_legacy_access_token_user (user_id)
);

CREATE TABLE legacy_search_tag
(
    id             BIGINT       NOT NULL PRIMARY KEY,
    group_name     VARCHAR(32)  NOT NULL,
    group_ordering INT          NOT NULL,
    group_color    VARCHAR(16)  NULL,
    name           VARCHAR(128) NOT NULL,
    ordering       INT          NOT NULL,
    int_value      INT          NULL,
    string_value   VARCHAR(128) NULL,
    INDEX idx_legacy_search_tag_group (group_ordering, ordering)
);
