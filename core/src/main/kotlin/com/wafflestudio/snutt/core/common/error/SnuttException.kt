package com.wafflestudio.snutt.core.common.error

import org.springframework.dao.DataIntegrityViolationException

open class SnuttException(
    val error: ErrorType = ErrorType.DEFAULT_ERROR,
    val title: String = error.title,
    val errorMessage: String = error.errorMessage,
    val displayMessage: String = error.displayMessage,
) : RuntimeException(errorMessage)

// DB 유일성 제약 위반을 도메인 오류로 바꿔 던진다
inline fun <T> conflictAs(
    errorType: ErrorType,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DataIntegrityViolationException) {
        throw SnuttException(errorType)
    }
