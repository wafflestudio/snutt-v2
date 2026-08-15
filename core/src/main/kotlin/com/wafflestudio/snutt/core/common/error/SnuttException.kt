package com.wafflestudio.snutt.core.common.error

import org.springframework.dao.DataIntegrityViolationException

open class SnuttException(
    val error: ErrorType = ErrorType.DEFAULT_ERROR,
    val title: String = error.title,
    val errorMessage: String = error.errorMessage,
    val displayMessage: String = error.displayMessage,
) : RuntimeException(errorMessage)

inline fun <T> conflictAs(
    errorType: ErrorType,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DataIntegrityViolationException) {
        throw SnuttException(errorType)
    }
