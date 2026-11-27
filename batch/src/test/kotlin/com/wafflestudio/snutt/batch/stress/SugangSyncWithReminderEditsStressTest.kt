package com.wafflestudio.snutt.batch.stress

import com.wafflestudio.snutt.batch.stress.AbstractSugangSyncUnderLoadStressTest.Companion.REMINDER_OPERATIONS
import com.wafflestudio.snutt.batch.stress.AbstractSugangSyncUnderLoadStressTest.Companion.SAFE_OPERATIONS
import com.wafflestudio.snutt.core.test.StressKnownFailure

/**
 * 재현된 버그: 동기화 중에 사용자가 리마인더를 바꾸거나 수업 시간을 고치면 발송 시각 교체(replaceSchedules)끼리
 * idx_reminder_schedule_fire gap 락 데드락이 난다. (발송 시각 어긋남과 리마인더 설정의 unique·외래 키 위반은 동기화가 시간표를 잠그고
 * 리마인더 설정도 시간표를 잠그면서 고쳐졌다.) 고치면 [StressTest]로 바꾼다.
 */
@StressKnownFailure
class SugangSyncWithReminderEditsStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-reminder-edits", SAFE_OPERATIONS + REMINDER_OPERATIONS)
