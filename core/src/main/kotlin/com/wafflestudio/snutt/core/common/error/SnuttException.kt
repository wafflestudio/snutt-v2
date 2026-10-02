package com.wafflestudio.snutt.core.common.error

import org.springframework.dao.DataIntegrityViolationException

open class SnuttException(
    val error: ErrorType,
    vararg args: Any,
    cause: Throwable? = null,
) : RuntimeException(cause) {
    override val message: String = error.displayMessage.format(*args)
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
