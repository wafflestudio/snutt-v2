package com.wafflestudio.snutt.api.error

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.UpstreamException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI

val ErrorType.problemType: URI
    get() = URI.create("/problems/${name.lowercase().replace('_', '-')}")

@RestControllerAdvice
class SnuttExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(SnuttException::class)
    fun handleSnuttException(e: SnuttException): ErrorResponse = errorResponse(e, e.error, e.displayMessage)

    @ExceptionHandler(UpstreamException::class)
    fun handleUpstreamException(e: UpstreamException): ErrorResponse {
        log.error("upstream failure: provider={} error={}", e.provider, e.error, e)
        return errorResponse(e, e.error)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ErrorResponse =
        errorResponse(e, ErrorType.INVALID_BODY_FIELD_VALUE).apply {
            body.setProperty(
                "errors",
                e.bindingResult.fieldErrors.map {
                    mapOf(
                        "detail" to it.defaultMessage.orEmpty(),
                        "pointer" to "#/" + it.field.replace(Regex("""\[(\d+)]"""), ".$1").replace('.', '/'),
                    )
                },
            )
        }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ErrorResponse = errorResponse(e, ErrorType.INVALID_BODY_FIELD_VALUE)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleArgumentTypeMismatch(e: MethodArgumentTypeMismatchException): ErrorResponse = errorResponse(e, ErrorType.INVALID_PARAMETER)

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(e: Exception): ErrorResponse {
        if (e is ErrorResponse) return e
        log.error("unhandled exception", e)
        return ErrorResponse.create(e, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MESSAGE)
    }

    private fun errorResponse(
        e: Exception,
        error: ErrorType,
        detail: String = error.displayMessage,
    ): ErrorResponse =
        ErrorResponse
            .builder(e, error.httpStatus, detail)
            .type(error.problemType)
            .title(error.title)
            .build()

    companion object {
        private const val INTERNAL_ERROR_MESSAGE = "서버에 문제가 있으니, 잠시 후 다시 시도해주세요"
    }
}
