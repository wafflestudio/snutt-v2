package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime

object ClassTimeUtils {
    fun timesOverlap(times: List<ClassPlaceAndTime>): Boolean =
        times.indices.any { i ->
            times.subList(i + 1, times.size).any { comparedTime -> twoTimesOverlap(times[i], comparedTime) }
        }

    fun timesOverlap(
        times1: List<ClassPlaceAndTime>,
        times2: List<ClassPlaceAndTime>,
    ): Boolean =
        times1.any { classTime1 ->
            times2.any { classTime2 -> twoTimesOverlap(classTime1, classTime2) }
        }

    fun twoTimesOverlap(
        time1: ClassPlaceAndTime,
        time2: ClassPlaceAndTime,
    ): Boolean =
        time1.day == time2.day &&
            time1.startMinute < time2.endMinute &&
            time1.endMinute > time2.startMinute
}
