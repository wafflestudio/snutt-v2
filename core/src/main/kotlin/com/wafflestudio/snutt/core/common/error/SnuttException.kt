package com.wafflestudio.snutt.core.common.error

open class SnuttException(
    val error: ErrorType = ErrorType.DEFAULT_ERROR,
    val title: String = error.title,
    val errorMessage: String = error.errorMessage,
    val displayMessage: String = error.displayMessage,
) : RuntimeException(errorMessage)
