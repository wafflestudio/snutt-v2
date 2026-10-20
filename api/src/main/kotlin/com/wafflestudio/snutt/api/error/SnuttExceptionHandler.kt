package com.wafflestudio.snutt.api.error

import com.wafflestudio.snutt.api.auth.UserAuthInterceptor
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.client.languageOf
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.UpstreamException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.HandlerMapping
import java.net.URI

val ErrorType.problemType: URI
    get() = URI.create("/problems/${name.lowercase().replace('_', '-')}")

@RestControllerAdvice
class SnuttExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(SnuttException::class)
    fun handleSnuttException(
        e: SnuttException,
        request: HttpServletRequest,
    ): ErrorResponse {
        if (e is UpstreamException) logUpstreamFailure(e, request)
        val language = request.language
        return errorResponse(e, e.error, language, e.displayMessage(language))
    }

    private fun logUpstreamFailure(
        e: UpstreamException,
        request: HttpServletRequest,
    ) {
        val userId = request.getAttribute(UserAuthInterceptor.USER_ID_ATTRIBUTE) as? Long
        val path = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: request.requestURI
        log.error(
            "upstream failure: {} {} provider={} -> {} userId={} query={}",
            request.method,
            path,
            e.provider,
            e.error.httpStatus.value(),
            userId,
            request.queryString,
            e,
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        e: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ErrorResponse =
        errorResponse(e, ErrorType.INVALID_REQUEST_BODY, request.language).apply {
            body.setProperty(
                "errors",
                e.bindingResult.fieldErrors.map {
                    mapOf(
                        "detail" to it.defaultMessage.orEmpty(),
                        "field" to it.field,
                    )
                },
            )
        }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(
        e: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ErrorResponse = errorResponse(e, ErrorType.INVALID_REQUEST_BODY, request.language)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun handleInvalidParameter(
        e: Exception,
        request: HttpServletRequest,
    ): ErrorResponse = errorResponse(e, ErrorType.INVALID_PARAMETER, request.language)

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        e: Exception,
        request: HttpServletRequest,
    ): ErrorResponse {
        if (e is ErrorResponse) return e
        log.error("unhandled exception", e)
        return ErrorResponse.create(e, HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MESSAGE.of(request.language))
    }

    private fun errorResponse(
        e: Exception,
        error: ErrorType,
        language: Language,
        detail: String = error.displayMessage.of(language),
    ): ErrorResponse =
        ErrorResponse
            .builder(e, error.httpStatus, detail)
            .type(error.problemType)
            .title(error.title.of(language))
            .build()

    companion object {
        private val INTERNAL_ERROR_MESSAGE =
            LocalizedMessage(ko = "서버에 문제가 있으니, 잠시 후 다시 시도해주세요", en = "Something went wrong on our end. Please try again in a moment")
    }
}

private val HttpServletRequest.language: Language
    get() = languageOf(this::getHeader)
