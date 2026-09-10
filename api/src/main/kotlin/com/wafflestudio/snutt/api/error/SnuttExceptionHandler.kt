package com.wafflestudio.snutt.api.error

import com.wafflestudio.snutt.api.auth.UserAuthInterceptor
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.UpstreamException
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.ErrorResponse as SpringErrorResponse

data class ErrorResponse(
    val errcode: Long,
    val title: String,
    val displayMessage: String,
)

@RestControllerAdvice
class SnuttExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(SnuttException::class)
    fun handleSnuttException(e: SnuttException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(e.error.httpStatus)
            .body(
                ErrorResponse(
                    errcode = e.error.errorCode,
                    title = e.title,
                    displayMessage = e.displayMessage,
                ),
            )

    @ExceptionHandler(UpstreamException::class)
    fun handleUpstreamException(
        e: UpstreamException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val user = request.getAttribute(UserAuthInterceptor.USER_ATTRIBUTE) as? User
        val path = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: request.requestURI
        log.error(
            "upstream failure: {} {} provider={} -> {} userId={} query={}",
            request.method,
            path,
            e.provider,
            e.error.httpStatus.value(),
            user?.id,
            request.queryString,
            e,
        )
        return ResponseEntity
            .status(e.error.httpStatus)
            .body(
                ErrorResponse(
                    errcode = e.error.errorCode,
                    title = e.error.title,
                    displayMessage = e.error.displayMessage,
                ),
            )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fieldName =
            e.bindingResult.fieldErrors
                .firstOrNull()
                ?.field ?: "unknown"
        val error = ErrorType.INVALID_BODY_FIELD_VALUE
        return ResponseEntity
            .status(error.httpStatus)
            .body(
                ErrorResponse(
                    errcode = error.errorCode,
                    title = error.title,
                    displayMessage = "잘못된 값입니다. (request body: $fieldName)",
                ),
            )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is SpringErrorResponse) {
            val status = e.statusCode.value()
            return ResponseEntity
                .status(status)
                .body(
                    ErrorResponse(
                        errcode = status * 100L,
                        title = "요청을 처리할 수 없습니다",
                        displayMessage = "요청을 처리할 수 없습니다",
                    ),
                )
        }
        log.error("unhandled exception", e)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ErrorResponse(
                    errcode = INTERNAL_ERROR_CODE,
                    title = INTERNAL_ERROR_MESSAGE,
                    displayMessage = INTERNAL_ERROR_MESSAGE,
                ),
            )
    }

    companion object {
        private const val INTERNAL_ERROR_CODE = 50000L
        private const val INTERNAL_ERROR_MESSAGE = "서버에 문제가 있으니, 잠시 후 다시 시도해주세요"
    }
}
