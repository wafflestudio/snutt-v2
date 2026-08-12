package com.wafflestudio.snutt.core.common.enums

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

// v1과 동일한 값: 월요일=0 … 일요일=6
enum class DayOfWeek(
    @JsonValue val value: Int,
    val korText: String,
) {
    MONDAY(0, "월"),
    TUESDAY(1, "화"),
    WEDNESDAY(2, "수"),
    THURSDAY(3, "목"),
    FRIDAY(4, "금"),
    SATURDAY(5, "토"),
    SUNDAY(6, "일"),
    ;

    companion object {
        private val valueMap = entries.associateBy { it.value }

        @JsonCreator
        fun fromValue(value: Int): DayOfWeek = valueMap[value] ?: throw IllegalArgumentException("unknown day-of-week value: $value")

        fun getOfValue(value: Int): DayOfWeek? = valueMap[value]
    }
}

@Converter(autoApply = true)
class DayOfWeekConverter : AttributeConverter<DayOfWeek, Int> {
    override fun convertToDatabaseColumn(attribute: DayOfWeek?): Int? = attribute?.value

    override fun convertToEntityAttribute(dbData: Int?): DayOfWeek? = dbData?.let(DayOfWeek::fromValue)
}
