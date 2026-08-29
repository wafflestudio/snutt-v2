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
