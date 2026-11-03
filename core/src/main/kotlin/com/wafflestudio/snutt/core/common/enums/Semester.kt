package com.wafflestudio.snutt.core.common.enums

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

enum class Semester(
    @JsonValue val value: Int,
    val fullName: String,
    val fullNameEn: String,
) {
    SPRING(1, "1학기", "Spring"),
    SUMMER(2, "여름학기", "Summer"),
    AUTUMN(3, "2학기", "Fall"),
    WINTER(4, "겨울학기", "Winter"),
    ;

    companion object {
        private val valueMap = entries.associateBy { it.value }

        @JsonCreator
        fun fromValue(value: Int): Semester = valueMap[value] ?: throw IllegalArgumentException("unknown semester value: $value")
    }
}

val Semester.localizedFullName: LocalizedMessage
    get() = LocalizedMessage(ko = fullName, en = fullNameEn)

fun yearAndSemesterLabel(
    year: Int,
    semester: Semester,
): LocalizedMessage = LocalizedMessage(ko = "$year-${semester.fullName}", en = "$year ${semester.fullNameEn}")

@Converter(autoApply = true)
class SemesterConverter : AttributeConverter<Semester, Int> {
    override fun convertToDatabaseColumn(attribute: Semester?): Int? = attribute?.value

    override fun convertToEntityAttribute(dbData: Int?): Semester? = dbData?.let(Semester::fromValue)
}
