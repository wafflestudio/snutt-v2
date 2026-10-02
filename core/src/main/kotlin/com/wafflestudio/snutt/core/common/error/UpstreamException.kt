package com.wafflestudio.snutt.core.common.error

class UpstreamException(
    error: ErrorType,
    val provider: String,
    cause: Throwable? = null,
) : SnuttException(error, cause = cause) {
    init {
        require(error.httpStatus.is5xxServerError) { "upstream error must be 5xx: $error" }
    }
}
