package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime

object SugangSnuClassTimeUtils {
    private val classTimeRegex =
        """^(?<day>[월화수목금토일])\((?<startHour>\d{2}):(?<startMinute>\d{2})~(?<endHour>\d{2}):(?<endMinute>\d{2})\)$""".toRegex()

    fun convertTextToClassTimeObject(
        classTimesTexts: List<String>,
        locationsTexts: List<String>,
    ): List<ClassPlaceAndTime> {
        val parsedTimes = classTimesTexts.filter { it.isNotBlank() }.map(::parseClassTime)
        val locations =
            when (locationsTexts.size) {
                parsedTimes.size -> locationsTexts
                1 -> List(parsedTimes.size) { locationsTexts.first() }
                0 -> List(parsedTimes.size) { "" }
                else -> throw IllegalArgumentException("시간과 강의실 수가 다르다: $classTimesTexts / $locationsTexts")
            }
        return parsedTimes
            .zip(locations)
            .groupBy({ it.first }, { it.second })
            .map { (time, roomTexts) ->
                ClassPlaceAndTime(
                    day = time.day,
                    place = roomTexts.joinToString("/"),
                    startMinute = time.startHour * 60 + time.startMinute,
                    endMinute = time.endHour * 60 + time.endMinute,
                )
            }.sortedWith(compareBy({ it.day.value }, { it.startMinute }))
    }

    private fun parseClassTime(text: String): ParsedClassTime {
        val groups = requireNotNull(classTimeRegex.find(text)).groups
        return ParsedClassTime(
            day = DayOfWeek.getByKoreanText(groups["day"]!!.value)!!,
            startHour = groups["startHour"]!!.value.toInt(),
            startMinute = groups["startMinute"]!!.value.toInt(),
            endHour = groups["endHour"]!!.value.toInt(),
            endMinute = groups["endMinute"]!!.value.toInt(),
        )
    }

    private data class ParsedClassTime(
        val day: DayOfWeek,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
    )
}
