package com.wafflestudio.snutt.core.common.enums

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

// v1과 동일한 값: 클라이언트가 학기를 숫자로 파싱한다
enum class Semester(
    @JsonValue val value: Int,
    val fullName: String,
) {
    SPRING(1, "1학기"),
    SUMMER(2, "여름학기"),
    AUTUMN(3, "2학기"),
    WINTER(4, "겨울학기"),
    ;

    companion object {
        private val valueMap = entries.associateBy { it.value }

        @JsonCreator
        fun fromValue(value: Int): Semester = valueMap[value] ?: throw IllegalArgumentException("unknown semester value: $value")

        fun getOfValue(value: Int): Semester? = valueMap[value]
    }
}

@Converter(autoApply = true)
class SemesterConverter : AttributeConverter<Semester, Int> {
    override fun convertToDatabaseColumn(attribute: Semester?): Int? = attribute?.value

    override fun convertToEntityAttribute(dbData: Int?): Semester? = dbData?.let(Semester::fromValue)
}
