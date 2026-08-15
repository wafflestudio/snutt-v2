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
