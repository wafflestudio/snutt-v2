package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.ZoneId

data class Schedule(
    val day: DayOfWeek,
    val minute: Int,
    val recentNotifiedAt: Instant? = null,
) : Comparable<Schedule> {
    fun plusMinutes(minutesToAdd: Int): Schedule {
        val minutesPerDay = 1440
        val totalMinutes = minute + minutesToAdd
        val daysToAdd = Math.floorDiv(totalMinutes, minutesPerDay)
        val newMinute = Math.floorMod(totalMinutes, minutesPerDay)
        val newDayIndex = Math.floorMod(day.value + daysToAdd, 7)
        return Schedule(DayOfWeek.getOfValue(newDayIndex)!!, newMinute)
    }

    override fun compareTo(other: Schedule): Int {
        val dayCompare = day.compareTo(other.day)
        return if (dayCompare != 0) dayCompare else minute.compareTo(other.minute)
    }

    companion object {
        fun fromInstant(
            instant: Instant,
            zoneId: ZoneId = ZoneId.of("Asia/Seoul"),
        ): Schedule {
            val localDateTime = instant.atZone(zoneId).toLocalDateTime()
            return Schedule(
                day = DayOfWeek.getOfValue(localDateTime.dayOfWeek.value - 1)!!,
                minute = localDateTime.hour * 60 + localDateTime.minute,
            )
        }
    }
}

// 발화 시각 비정규화 컬럼(next_day/next_minute)은 매분 스케줄러가 인덱스로 조회한다
@Entity
@Table(name = "timetable_lecture_reminder")
class TimetableLectureReminder(
    var timetableLectureId: Long,
    var offsetMinutes: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    var scheduleList: List<Schedule>,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var nextDay: Int? = null,
    @JdbcTypeCode(SqlTypes.SMALLINT)
    var nextMinute: Int? = null,
    var recentNotifiedAt: Instant? = null,
) : ExternalIdEntity() {
    // scheduleList에서 현재 시각 이후 가장 가까운 발화 시각으로 next_*를 갱신한다
    fun recomputeNextFire(now: Instant = Instant.now()) {
        val nowSchedule = Schedule.fromInstant(now)
        val next =
            scheduleList
                .sorted()
                .firstOrNull { it >= nowSchedule }
                ?: scheduleList.minOrNull()
        if (next != null) {
            nextDay = next.day.value
            nextMinute = next.minute
        }
    }
}
