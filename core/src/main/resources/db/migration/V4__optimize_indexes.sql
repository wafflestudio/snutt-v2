ALTER TABLE diary_submission
    ADD INDEX idx_diary_submission_user_created (user_id, created_at DESC),
    DROP INDEX idx_diary_submission_user;

ALTER TABLE friend
    ADD INDEX idx_friend_from_user_accepted_created (from_user_id, is_accepted, created_at DESC),
    ADD INDEX idx_friend_to_user_accepted_created (to_user_id, is_accepted, created_at DESC),
    DROP INDEX idx_friend_from_user,
    DROP INDEX idx_friend_to_user;

ALTER TABLE lecture_class_time
    ADD INDEX idx_lecture_class_time_lecture_slot (lecture_id, day, start_minute, end_minute),
    DROP INDEX idx_lecture_class_time_lecture;

ALTER TABLE lecture
    ADD INDEX idx_lecture_classification_course (classification, course_id),
    DROP INDEX idx_lecture_classification;

ALTER TABLE timetable_lecture_reminder_schedule
    ADD INDEX idx_reminder_schedule_slot_notified (day, minute, reminder_id, recent_notified_at),
    DROP INDEX idx_reminder_schedule_slot;
