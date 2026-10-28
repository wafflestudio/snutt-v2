ALTER TABLE notification
    ADD COLUMN title_en VARCHAR(255) NULL AFTER title,
    ADD COLUMN message_en TEXT NULL AFTER message;

ALTER TABLE user_device
    ADD COLUMN language VARCHAR(8) NOT NULL DEFAULT 'KO' AFTER app_version;
