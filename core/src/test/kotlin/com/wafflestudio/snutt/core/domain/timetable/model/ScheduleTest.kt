package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleTest {
    @Test
    fun minusOffsetWithinSameDay() {
        // 월 09:30 수업의 10분 전 알림 → 월 09:20
        val schedule = Schedule(DayOfWeek.MONDAY, 570).plusMinutes(-10)
        assertThat(schedule).isEqualTo(Schedule(DayOfWeek.MONDAY, 560))
    }

    @Test
    fun plusMinutesCrossesIntoNextDay() {
        // 일 23:50에 20분 후 알림 → 월 00:10 (요일 전진)
        val schedule = Schedule(DayOfWeek.SUNDAY, 1430).plusMinutes(20)
        assertThat(schedule).isEqualTo(Schedule(DayOfWeek.MONDAY, 10))
    }

    @Test
    fun minusOffsetCrossesIntoPreviousDay() {
        // 월 00:05 수업의 10분 전 알림 → 일 23:55 (자정을 넘어 전날로)
        val schedule = Schedule(DayOfWeek.MONDAY, 5).plusMinutes(-10)
        assertThat(schedule).isEqualTo(Schedule(DayOfWeek.SUNDAY, 1435))
    }

    @Test
    fun plusMinutesWrapsEndOfWeek() {
        // 토 23:59 + 1분 → 일 00:00
        val schedule = Schedule(DayOfWeek.SATURDAY, 1439).plusMinutes(1)
        assertThat(schedule).isEqualTo(Schedule(DayOfWeek.SUNDAY, 0))
    }

    @Test
    fun orderingSortsByDayThenMinute() {
        val monday = Schedule(DayOfWeek.MONDAY, 600)
        val tuesdayEarly = Schedule(DayOfWeek.TUESDAY, 30)
        val mondayLate = Schedule(DayOfWeek.MONDAY, 1300)
        assertThat(listOf(mondayLate, tuesdayEarly, monday).sorted())
            .containsExactly(monday, mondayLate, tuesdayEarly)
    }
}
