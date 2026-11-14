ALTER TABLE diary_submission
    ADD INDEX idx_diary_submission_user_created (user_id, created_at DESC),
    ADD INDEX idx_diary_submission_user_lecture_created (user_id, lecture_id, created_at DESC),
    DROP INDEX idx_diary_submission_user;

ALTER TABLE friend
    ADD INDEX idx_friend_from_user_accepted_created (from_user_id, is_accepted, created_at DESC),
    ADD INDEX idx_friend_to_user_accepted_created (to_user_id, is_accepted, created_at DESC),
    DROP INDEX idx_friend_from_user,
    DROP INDEX idx_friend_to_user;

ALTER TABLE lecture_class_time
    ADD INDEX idx_lecture_class_time_lecture_slot (lecture_id, day, start_minute, end_minute),
    DROP INDEX idx_lecture_class_time_lecture;

ALTER TABLE evaluation
    ADD INDEX idx_evaluation_visible_id (is_hidden, id DESC);

ALTER TABLE lecture
    ADD INDEX idx_lecture_course_classification (course_id, classification);

ALTER TABLE timetable_lecture_reminder_schedule
    ADD INDEX idx_reminder_schedule_slot_notified (day, minute, recent_notified_at, reminder_id),
    DROP INDEX idx_reminder_schedule_slot;
