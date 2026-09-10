package com.wafflestudio.snutt.core.common.error

class UpstreamException(
    val error: ErrorType,
    val provider: String,
    override val cause: Throwable? = null,
) : RuntimeException("upstream failure: $provider", cause) {
    init {
        require(error.httpStatus.is5xxServerError) { "upstream error must be 5xx: $error" }
    }
}
