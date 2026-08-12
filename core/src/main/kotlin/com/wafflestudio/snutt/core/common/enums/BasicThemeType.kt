package com.wafflestudio.snutt.core.common.enums

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

// v1과 동일한 값: 클라이언트가 내장 테마 번호로 파싱한다 (SNUTT=0 … LAWN=5)
enum class BasicThemeType(
    @JsonValue val value: Int,
    val displayName: String,
) {
    SNUTT(0, "SNUTT"),
    FALL(1, "가을"),
    MODERN(2, "모던"),
    CHERRY_BLOSSOM(3, "벚꽃"),
    ICE(4, "얼음"),
    LAWN(5, "잔디"),
    ;

    companion object {
        const val COLOR_COUNT = 9

        @JsonCreator
        fun fromValue(value: Int): BasicThemeType =
            entries.find { it.value == value } ?: throw IllegalArgumentException("unknown basic theme value: $value")

        fun from(displayName: String): BasicThemeType? = entries.find { it.displayName == displayName }
    }
}

@Converter(autoApply = true)
class BasicThemeTypeConverter : AttributeConverter<BasicThemeType, Int> {
    override fun convertToDatabaseColumn(attribute: BasicThemeType?): Int? = attribute?.value

    override fun convertToEntityAttribute(dbData: Int?): BasicThemeType? = dbData?.let(BasicThemeType::fromValue)
}
