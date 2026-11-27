package com.wafflestudio.snutt.api.concurrency

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** 시간표 행 잠금(findForUpdateByIdAndUserId)과 사용자 행 잠금(findForUpdateById)이 막는 race. 리마인더 설정도 시간표를 잠근다. */
class TimetableConcurrencyTest : AbstractConcurrencyTest() {
    @Autowired lateinit var timetableService: TimetableService

    @Autowired lateinit var timetableThemeService: TimetableThemeService

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

    @Test
    fun `같은 강의에 리마인더 설정을 동시에 보내도 오류 없이 리마인더는 하나다`() {
        scenarios.setReminderOnSameLecture(REQUESTS).runContended(contention)
    }

    /**
     * 조회는 트랜잭션 없이 timetable(테마 id)과 timetable_lecture(palette_index)를 따로 읽는다. 조회가 시간표를 읽은 뒤 시간표 테마 변경이
     * 커밋되면 조회는 옛 테마(3색)에 새 테마(9색) 기준 index를 붙인다. 그 인터리빙을 순서대로 재현한다. 500이 아니라 팔레트 안의 색을 줘야 한다.
     */
    @Test
    fun `시간표를 읽은 뒤 테마 변경이 커밋돼도 조회는 팔레트 밖 index로 실패하지 않는다`() {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val threeColors = (1..3).map { ColorSet("#00000$it", "#ffffff") }
        val smallTheme = timetableThemeService.addTheme(user.id!!, "세 가지 색", threeColors)
        val timetable = fixtures.timetable(user, autumn, themeId = smallTheme.id)
        repeat(5) { fixtures.addLecture(timetable, fixtures.lecture(autumn), paletteIndex = it % 3) }
        val readBeforeChange = timetableService.getTimetable(user.id!!, timetable.id!!)

        timetableService.modifyTimetableTheme(user.id!!, timetable.id!!, timetableThemeService.builtinThemeId("snutt"))
        val display = timetableService.displayOf(readBeforeChange)

        assertEquals(5, display.lectures.size)
        assertTrue(display.lectures.all { it.color in threeColors })
    }
}
