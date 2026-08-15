package com.wafflestudio.snutt.v1compat.error

import com.wafflestudio.snutt.core.common.error.SnuttException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class V1ErrorResponse(
    val errcode: Long,
    val title: String,
    val message: String,
    val displayMessage: String,
)

fun SnuttException.toV1ErrorResponse(): ResponseEntity<V1ErrorResponse> =
    ResponseEntity
        .status(error.httpStatus)
        .body(
            V1ErrorResponse(
                errcode = error.errorCode,
                title = title,
                message = errorMessage,
                displayMessage = displayMessage,
            ),
        )

@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(basePackages = ["com.wafflestudio.snutt.v1compat"])
class V1CompatExceptionHandler {
    @ExceptionHandler(SnuttException::class)
    fun handleSnuttException(e: SnuttException): ResponseEntity<V1ErrorResponse> = e.toV1ErrorResponse()
}
