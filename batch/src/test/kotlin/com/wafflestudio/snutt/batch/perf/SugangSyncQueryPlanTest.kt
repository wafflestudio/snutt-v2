package com.wafflestudio.snutt.batch.perf

import com.wafflestudio.snutt.batch.sugangsnu.SugangSyncPendingRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class SugangSyncQueryPlanTest : AbstractBatchPerfTest() {
    @Autowired lateinit var pendingRepository: SugangSyncPendingRepository

    // (processed, timetable_id) 인덱스만 있으면 processed 조건이 없는 이 쿼리는 인덱스 전체(type index)를 읽는다.
    // 시간표를 1,000개씩 펼칠 때마다 남은 일 전체를 읽게 되어 시간표 수의 제곱으로 는다.
    @Test
    fun `이미 남긴 시간표 반영은 시간표 id 인덱스로 그 시간표의 행만 읽는다`() {
        val chunk = (1L..1_000L).toList() // 한 번에 묻는 시간표 수(IN_CHUNK_SIZE)
        val total = perf.rowCounts.getValue("sugang_sync_pending_timetable")

        explainOne { pendingRepository.findTimetablePairs(chunk) }
            .assertKey("sugang_sync_pending_timetable", "idx_sugang_sync_pending_timetable_timetable")
            .assertAccess("sugang_sync_pending_timetable", "range")
            .assertRowsAtMost("sugang_sync_pending_timetable", total / 4L)
    }
}
