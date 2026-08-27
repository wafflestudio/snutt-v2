package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.ZoneId

data class Schedule(
    val day: DayOfWeek,
    val minute: Int,
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

@Entity
@Table(name = "timetable_lecture_reminder")
class TimetableLectureReminder(
    var timetableLectureId: Long,
    var offsetMinutes: Int,
) : BaseEntity()

@Entity
@Table(name = "timetable_lecture_reminder_schedule")
class TimetableLectureReminderSchedule(
    var reminderId: Long,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var day: DayOfWeek,
    var minute: Int,
    var recentNotifiedAt: Instant? = null,
) : BaseEntity() {
    fun toSchedule() = Schedule(day, minute)
}
