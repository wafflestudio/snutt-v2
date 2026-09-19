ALTER TABLE `user`
    ADD INDEX idx_user_local_id (local_id),
    ADD INDEX idx_user_nickname (nickname, nickname_tag);

ALTER TABLE evaluation
    DROP INDEX idx_evaluation_course_visible,
    ADD INDEX idx_evaluation_course_visible (course_id, is_hidden, year, semester, id);

ALTER TABLE timetable_lecture_reminder_schedule
    DROP INDEX idx_reminder_schedule_slot,
    ADD INDEX idx_reminder_schedule_slot (day, minute, reminder_id);

ALTER TABLE lecture_class_time
    DROP INDEX idx_lecture_class_time_slot;

ALTER TABLE notification
    DROP INDEX idx_notification_created;
