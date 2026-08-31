package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleTest {
    @Test
    fun minusOffsetWithinSameDay() {
        val schedule = Schedule(DayOfWeek.MONDAY, 570).plusMinutes(-10)
        assertThat(schedule).isEqualTo(Schedule(DayOfWeek.MONDAY, 560))
    }

    @Test
    fun plusMinutesCrossesIntoNextDay() {
        val schedule = Schedule(DayOfWeek.SUNDAY, 1430).plusMinutes(20)
        assertThat(schedule).isEqualTo(Schedule(DayOfWeek.MONDAY, 10))
    }

    @Test
    fun minusOffsetCrossesIntoPreviousDay() {
        val schedule = Schedule(DayOfWeek.MONDAY, 5).plusMinutes(-10)
        assertThat(schedule).isEqualTo(Schedule(DayOfWeek.SUNDAY, 1435))
    }

    @Test
    fun plusMinutesWrapsEndOfWeek() {
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
