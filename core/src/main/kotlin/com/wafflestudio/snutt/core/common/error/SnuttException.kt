package com.wafflestudio.snutt.core.common.error

import org.springframework.dao.DataIntegrityViolationException

open class SnuttException(
    val error: ErrorType = ErrorType.DEFAULT_ERROR,
    val title: String = error.title,
    val displayMessage: String = error.displayMessage,
) : RuntimeException(displayMessage)

inline fun <T> conflictAs(
    errorType: ErrorType,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DataIntegrityViolationException) {
        throw SnuttException(errorType)
    }
