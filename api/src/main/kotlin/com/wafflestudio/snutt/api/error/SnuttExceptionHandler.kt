package com.wafflestudio.snutt.api.error

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.ErrorResponse as SpringErrorResponse

data class ErrorResponse(
    val errcode: Long,
    val title: String,
    val message: String,
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
                    message = e.errorMessage,
                    displayMessage = e.displayMessage,
                ),
            )

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
                    message = "잘못된 값입니다. (request body: $fieldName)",
                    displayMessage = error.displayMessage,
                ),
            )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(e: Exception): ResponseEntity<ErrorResponse> {
        // 라우팅·메서드·바인딩 실패는 Spring이 상태를 정해 둔다(미매칭 경로 404 등).
        // errcode는 ErrorType과 같은 규칙(<상태코드><일련번호>)을 따른다
        if (e is SpringErrorResponse) {
            val status = e.statusCode.value()
            return ResponseEntity
                .status(status)
                .body(
                    ErrorResponse(
                        errcode = status * 100L,
                        title = "요청을 처리할 수 없습니다",
                        message = e.body.detail ?: e.message.orEmpty(),
                        displayMessage = "요청을 처리할 수 없습니다",
                    ),
                )
        }
        log.error("unhandled exception", e)
        val error = ErrorType.DEFAULT_ERROR
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ErrorResponse(
                    errcode = error.errorCode,
                    title = error.title,
                    message = error.errorMessage,
                    displayMessage = error.displayMessage,
                ),
            )
    }
}
