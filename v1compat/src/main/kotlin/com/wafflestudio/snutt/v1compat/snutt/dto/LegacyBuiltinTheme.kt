package com.wafflestudio.snutt.v1compat.snutt.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException

private val LEGACY_BUILTIN_CODES = listOf("snutt", "fall", "modern", "blossom", "ice", "lawn")

internal fun legacyBuiltinCode(value: Int): String =
    LEGACY_BUILTIN_CODES.getOrNull(value)
        ?: throw SnuttException(ErrorType.INVALID_PARAMETER)

internal fun legacyThemeValue(builtinCode: String): Int =
    LEGACY_BUILTIN_CODES.indexOf(builtinCode).takeIf { it >= 0 } ?: BasicThemeType.SNUTT.value

enum class BasicThemeType(
    @JsonValue val value: Int,
) {
    SNUTT(0),
    FALL(1),
    MODERN(2),
    CHERRY_BLOSSOM(3),
    ICE(4),
    LAWN(5),
    ;

    companion object {
        @JsonCreator
        fun fromValue(value: Int): BasicThemeType =
            entries.find { it.value == value } ?: throw IllegalArgumentException("unknown basic theme value: $value")
    }
}
