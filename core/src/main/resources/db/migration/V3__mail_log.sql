CREATE TABLE mail_log
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    type                VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    email               VARCHAR(255) NULL,
    requested_local_id  VARCHAR(64) COLLATE utf8mb4_0900_bin NULL,
    subject             VARCHAR(255) NULL,
    body                TEXT         NULL,
    error_message       TEXT         NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    INDEX idx_mail_log_email (email),
    INDEX idx_mail_log_requested_local_id (requested_local_id),
    INDEX idx_mail_log_created (created_at)
);
