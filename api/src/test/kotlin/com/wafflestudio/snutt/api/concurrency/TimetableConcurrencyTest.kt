package com.wafflestudio.snutt.api.concurrency

import org.junit.jupiter.api.Test

/** 시간표 행 잠금(findForUpdateByIdAndUserId)과 사용자 행 잠금(findForUpdateById)이 막는 race. */
class TimetableConcurrencyTest : AbstractConcurrencyTest() {
    @Test
    fun `같은 시간표에 시간이 겹치는 강의를 동시에 추가하면 하나만 들어간다`() {
        scenarios.overlappingLecturesIntoOneTimetable(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 강의를 같은 시간표에 동시에 추가하면 한 번만 들어간다`() {
        scenarios.sameLectureIntoOneTimetable(REQUESTS).runContended(contention)
    }

    @Test
    fun `테마 팔레트를 줄이는 동안 추가된 강의도 줄어든 팔레트 안의 색을 받는다`() {
        scenarios.themePaletteShrinkWhileAddingLectures(REQUESTS).runContended(contention)
    }

    @Test
    fun `빈 학기에 시간표를 동시에 여러 개 만들어도 대표 시간표는 하나다`() {
        scenarios.firstTimetablesOfSemester(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 제목의 시간표를 동시에 만들면 하나만 만들어진다`() {
        scenarios.sameTitleTimetables(REQUESTS).runContended(contention)
    }

    @Test
    fun `모든 시간표를 동시에 지워도 마지막 하나는 남는다`() {
        scenarios.deleteEveryTimetable(REQUESTS).runContended(contention)
    }

    @Test
    fun `같은 학기 시간표들을 동시에 대표로 지정해도 대표 시간표는 하나다`() {
        scenarios.setPrimaryOnEveryTimetable(REQUESTS).runContended(contention)
    }
}
