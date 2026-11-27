package com.wafflestudio.snutt.batch.stress

import com.wafflestudio.snutt.batch.stress.AbstractSugangSyncUnderLoadStressTest.Companion.ADD_OPERATION
import com.wafflestudio.snutt.batch.stress.AbstractSugangSyncUnderLoadStressTest.Companion.EDIT_OPERATIONS
import com.wafflestudio.snutt.batch.stress.AbstractSugangSyncUnderLoadStressTest.Companion.REMINDER_OPERATIONS
import com.wafflestudio.snutt.batch.stress.AbstractSugangSyncUnderLoadStressTest.Companion.SAFE_OPERATIONS
import com.wafflestudio.snutt.core.test.StressKnownFailure

/**
 * 재현된 버그: 동기화 중에 사용자가 동기화 대상 강의를 고치거나 지우면
 * (1) 데드락: 동기화는 timetable_lecture X락(겹쳐서 밀려난·폐강 강의 삭제) → timetable X락(touchUpdatedAt) 순으로, 강의 수정은 timetable X락 →
 *     timetable_lecture X락 순으로 잡는다. 사용자 요청이 희생돼 500이 된다.
 * (2) 잡 롤백: 사용자가 리마인더가 걸린 강의를 지우면, 동기화가 스냅숏으로 본 그 강의의 리마인더 발송 시각을 다시 쓰다가
 *     (`recomputeForTimetableLecture`) 이미 지워진 리마인더를 참조해 외래 키 위반이 나고 학기 동기화 전체가 롤백된다.
 * 고치면 [EDIT_OPERATIONS]를 [SAFE_OPERATIONS]로 옮긴다.
 */
@StressKnownFailure
class SugangSyncWithLectureEditsStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-lecture-edits", SAFE_OPERATIONS + EDIT_OPERATIONS)

/**
 * 재현된 버그: 동기화 중에 사용자가 강의를 담으면 (1) 동기화가 폐강 강의를 지울 때 사용자가 방금 담은 행 때문에 외래 키(RESTRICT) 위반이 나
 * 잡 전체가 롤백되고(그 학기 변경이 하나도 반영되지 않는다), (2) 동기화가 timetable을 잠그지 않고 스냅숏으로 겹침을 판단하므로 같은 시간표에서
 * 시간이 겹치는 강의가 남을 수 있고, (3) touchUpdatedAt(timetable X락)과 강의 추가(timetable X락 → lecture FK S락)가 데드락이 난다.
 * 고치면 [StressTest]로 바꾼다.
 */
@StressKnownFailure
class SugangSyncWithLectureAddsStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-lecture-adds", SAFE_OPERATIONS + ADD_OPERATION)

/**
 * 재현된 버그: 동기화 중에 사용자가 리마인더를 바꾸거나 수업 시간을 고치면 리마인더 발송 시각이 실제 수업 시간과 어긋난 채 남고(리마인더 설정이
 * 동기화 전 수업 시간으로 계산된다), 발송 시각 교체끼리 gap 락 데드락, 시간표를 잠그지 않는 리마인더 설정의 unique·외래 키 위반·낙관적 락 실패가 난다.
 * 고치면 [StressTest]로 바꾼다.
 */
@StressKnownFailure
class SugangSyncWithReminderEditsStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-reminder-edits", SAFE_OPERATIONS + REMINDER_OPERATIONS)
