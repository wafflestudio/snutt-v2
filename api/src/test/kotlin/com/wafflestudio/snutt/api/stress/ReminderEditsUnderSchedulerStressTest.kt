package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.core.test.StressKnownFailure

/**
 * 재현된 버그: 리마인더 설정·수업 시간 변경이 겹치면 다른 리마인더끼리(다른 사용자여도) 데드락이 난다. 발송 시각 교체(`replaceSchedules`:
 * reminder_id로 DELETE 후 INSERT)가 idx_reminder_schedule_fire에 gap 락을 잡고, 인덱스에서 이웃한 리마인더의 INSERT가 그 gap을 기다린다.
 * (정리와 겹친 외래 키 위반 500은 리마인더 설정과 정리가 모두 시간표를 먼저 잠그면서 고쳐졌다.) 고치면 [StressTest]로 바꾼다.
 */
@StressKnownFailure
class ReminderEditsUnderSchedulerStressTest :
    AbstractSchedulerUnderLoadStressTest("scheduler-under-load-with-reminder-edits", reminderEdits = true, concurrentCleanup = false)
