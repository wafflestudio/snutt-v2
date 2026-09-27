package com.wafflestudio.snutt.core.common.error

import org.springframework.dao.DataIntegrityViolationException

open class SnuttException(
    val error: ErrorType,
    vararg args: Any,
) : RuntimeException() {
    val displayMessage: String = error.displayMessage.format(*args)

    override val message: String
        get() = displayMessage
}

inline fun <T> conflictAs(
    errorType: ErrorType,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DataIntegrityViolationException) {
        throw SnuttException(errorType)
    }
