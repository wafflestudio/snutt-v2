package com.wafflestudio.snutt.v1compat.snutt.dto

import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException

private val LEGACY_BUILTIN_CODES = listOf("snutt", "fall", "modern", "blossom", "ice", "lawn")

internal fun legacyBuiltinCode(value: Int): String =
    LEGACY_BUILTIN_CODES.getOrNull(value)
        ?: throw SnuttException(ErrorType.INVALID_PARAMETER)

internal fun legacyThemeValue(builtinCode: String): Int =
    LEGACY_BUILTIN_CODES.indexOf(builtinCode).takeIf { it >= 0 } ?: BasicThemeType.SNUTT.value
