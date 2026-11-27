package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.core.test.StressKnownFailure

/**
 * 재현된 버그: 지난 학기 리마인더 정리(`deletePastSemesterReminders`, 매일 04시)가 강의를 고치는 요청과 데드락이 난다.
 * 정리 쿼리(DELETE JOIN)는 리마인더가 달린 모든 timetable_lecture → timetable 행을 S락으로 읽고, 강의 수정은 timetable X락 → timetable_lecture
 * X락 순으로 잡는다. 지난 학기와 상관없는 강의 제목 변경도 걸린다. 고치면 [StressTest]로 바꾼다.
 */
@StressKnownFailure
class ReminderCleanupUnderLoadStressTest :
    AbstractSchedulerUnderLoadStressTest("scheduler-under-load-with-cleanup", reminderEdits = false, concurrentCleanup = true)

/**
 * 재현된 버그: 리마인더 설정·수업 시간 변경이 겹치면 다른 리마인더끼리(다른 사용자여도) 데드락이 난다. 발송 시각 교체(`replaceSchedules`:
 * reminder_id로 DELETE 후 INSERT)가 idx_reminder_schedule_fire에 gap 락을 잡고, 인덱스에서 이웃한 리마인더의 INSERT가 그 gap을 기다린다.
 * 정리를 함께 돌리면 정리가 지운 리마인더에 발송 시각을 INSERT해 외래 키 위반 500도 난다(`modifyReminder`는 시간표를 잠그지 않는다).
 * 고치면 [StressTest]로 바꾼다.
 */
@StressKnownFailure
class ReminderEditsUnderSchedulerStressTest :
    AbstractSchedulerUnderLoadStressTest("scheduler-under-load-with-reminder-edits", reminderEdits = true, concurrentCleanup = false)
