package com.wafflestudio.snutt.core.domain.theme.model

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException

data class ColorSet(
    val backgroundColor: String,
    val foregroundColor: String,
) {
    init {
        if (!HEX_COLOR.matches(backgroundColor) || !HEX_COLOR.matches(foregroundColor)) {
            throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
        }
    }

    companion object {
        private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")
    }
}
