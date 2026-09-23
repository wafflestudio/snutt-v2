package com.wafflestudio.snutt.v1compat.error

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.UpstreamException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class V1ErrorResponse(
    val errcode: Long,
    val title: String,
    val message: String,
    val displayMessage: String,
)

private val V1_ERROR_CODE_MAP =
    mapOf(
        ErrorType.INVALID_TIMETABLE_TITLE to 0x1007,
        ErrorType.INVALID_TIME to 0x100C,
        ErrorType.WRONG_API_KEY to 0x2000,
        ErrorType.NO_USER_TOKEN to 0x2001,
        ErrorType.WRONG_USER_TOKEN to 0x2002,
        ErrorType.USER_NOT_ADMIN to 0x2003,
        ErrorType.WRONG_LOCAL_ID to 0x2004,
        ErrorType.WRONG_PASSWORD to 0x2005,
        ErrorType.INVALID_LOCAL_ID to 0x3000,
        ErrorType.INVALID_PASSWORD to 0x3001,
        ErrorType.DUPLICATE_LOCAL_ID to 0x3002,
        ErrorType.DUPLICATE_TIMETABLE_TITLE to 0x3003,
        ErrorType.DUPLICATE_LECTURE to 0x3004,
        ErrorType.WRONG_SEMESTER to 0x300A,
        ErrorType.INVALID_TIMETABLE_SEMESTER to 0x300B,
        ErrorType.LECTURE_TIME_OVERLAP to 0x300C,
        ErrorType.CANNOT_RESET_CUSTOM_LECTURE to 0x300D,
        ErrorType.INVALID_EMAIL to 0x300F,
        ErrorType.USER_EMAIL_IS_NOT_VERIFIED to 0x3011,
        ErrorType.LECTURE_NOT_FOUND to 0x4003,
        ErrorType.USER_NOT_FOUND to 0x4004,
        ErrorType.TIMETABLE_LECTURE_NOT_FOUND to 0x4005,
        ErrorType.DIARY_SUBMISSION_TOO_FREQUENT to 40028L,
        ErrorType.DUPLICATE_NICKNAME to 40031L,
    )

fun SnuttException.toV1ErrorResponse(): ResponseEntity<V1ErrorResponse> =
    ResponseEntity
        .status(error.httpStatus)
        .body(
            V1ErrorResponse(
                errcode = V1_ERROR_CODE_MAP[error] ?: error.errorCode,
                title = title,
                message = displayMessage,
                displayMessage = displayMessage,
            ),
        )

@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(basePackages = ["com.wafflestudio.snutt.v1compat"])
class V1CompatExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(SnuttException::class)
    fun handleSnuttException(e: SnuttException): ResponseEntity<V1ErrorResponse> = e.toV1ErrorResponse()

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(): ResponseEntity<V1ErrorResponse> = SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE).toV1ErrorResponse()

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun handleInvalidParameter(): ResponseEntity<V1ErrorResponse> = SnuttException(ErrorType.INVALID_PARAMETER).toV1ErrorResponse()

    @ExceptionHandler(UpstreamException::class)
    fun handleUpstreamException(e: UpstreamException): ResponseEntity<V1ErrorResponse> {
        log.error("upstream failure: provider={} error={}", e.provider, e.error, e)
        val legacy = if (e.error == ErrorType.SOCIAL_PROVIDER_UNAVAILABLE) ErrorType.SOCIAL_CONNECT_FAIL else e.error
        return ResponseEntity
            .status(legacy.httpStatus)
            .body(V1ErrorResponse(legacy.errorCode, legacy.title, legacy.displayMessage, legacy.displayMessage))
    }
}
