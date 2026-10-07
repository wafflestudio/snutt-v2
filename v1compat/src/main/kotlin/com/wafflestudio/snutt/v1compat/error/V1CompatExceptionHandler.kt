package com.wafflestudio.snutt.v1compat.error

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.UpstreamException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

private data class V1Error(
    val status: HttpStatus,
    val errcode: Long,
)

private val ErrorType.v1: V1Error
    get() =
        when (this) {
            ErrorType.INVALID_PARAMETER -> V1Error(HttpStatus.BAD_REQUEST, 40001)
            ErrorType.INVALID_REQUEST_BODY -> V1Error(HttpStatus.BAD_REQUEST, 40002)
            ErrorType.INVALID_REGISTRATION_FOR_PREVIOUS_SEMESTER_COURSE -> V1Error(HttpStatus.BAD_REQUEST, 40005)
            ErrorType.INVALID_NICKNAME -> V1Error(HttpStatus.BAD_REQUEST, 40008)
            ErrorType.INVALID_DISPLAY_NAME -> V1Error(HttpStatus.BAD_REQUEST, 40009)
            ErrorType.CANNOT_DELETE_LAST_TIMETABLE -> V1Error(HttpStatus.BAD_REQUEST, 40010)
            ErrorType.INVALID_THEME_COLOR_COUNT -> V1Error(HttpStatus.BAD_REQUEST, 40012)
            ErrorType.NOT_DEFAULT_THEME -> V1Error(HttpStatus.BAD_REQUEST, 40014)
            ErrorType.TOO_MANY_FILES -> V1Error(HttpStatus.BAD_REQUEST, 40015)
            ErrorType.EMAIL_ALREADY_VERIFIED -> V1Error(HttpStatus.BAD_REQUEST, 40016)
            ErrorType.TOO_MANY_VERIFICATION_CODE_REQUESTS -> V1Error(HttpStatus.BAD_REQUEST, 40017)
            ErrorType.INVALID_VERIFICATION_CODE -> V1Error(HttpStatus.BAD_REQUEST, 40018)
            ErrorType.ALREADY_LOCAL_ACCOUNT -> V1Error(HttpStatus.BAD_REQUEST, 40019)
            ErrorType.ALREADY_SOCIAL_ACCOUNT -> V1Error(HttpStatus.BAD_REQUEST, 40020)
            ErrorType.NOT_PUBLISHED_THEME -> V1Error(HttpStatus.BAD_REQUEST, 40022)
            ErrorType.CANNOT_DELETE_PUBLISHED_THEME -> V1Error(HttpStatus.BAD_REQUEST, 40023)
            ErrorType.TIMETABLE_LECTURE_REMINDER_INVALID_TIME -> V1Error(HttpStatus.BAD_REQUEST, 40024)
            ErrorType.INVALID_DIARY_QUESTION -> V1Error(HttpStatus.BAD_REQUEST, 40026)
            ErrorType.DIARY_COMMENT_TOO_LONG -> V1Error(HttpStatus.BAD_REQUEST, 40027)
            ErrorType.EVALUATION_CONTENT_BLANK -> V1Error(HttpStatus.BAD_REQUEST, 40028)
            ErrorType.EVALUATION_RATING_OUT_OF_RANGE -> V1Error(HttpStatus.BAD_REQUEST, 40029)
            ErrorType.EVALUATION_REPORT_CONTENT_BLANK -> V1Error(HttpStatus.BAD_REQUEST, 40030)
            ErrorType.INVALID_TIMETABLE_TITLE -> V1Error(HttpStatus.BAD_REQUEST, 0x1007)
            ErrorType.INVALID_CLASS_TIME -> V1Error(HttpStatus.BAD_REQUEST, 0x100C)
            ErrorType.INVALID_EVALUATION_SORT -> V1Error(HttpStatus.BAD_REQUEST, 40034)
            ErrorType.INVALID_CURSOR -> V1Error(HttpStatus.BAD_REQUEST, 40035)
            ErrorType.DIARY_SUBMISSION_TOO_FREQUENT -> V1Error(HttpStatus.BAD_REQUEST, 40028)
            ErrorType.SOCIAL_LOGIN_FAILED -> V1Error(HttpStatus.UNAUTHORIZED, 40100)
            ErrorType.INVALID_APPLE_LOGIN_TOKEN -> V1Error(HttpStatus.UNAUTHORIZED, 40101)
            ErrorType.EXPIRED_ACCESS_TOKEN -> V1Error(HttpStatus.UNAUTHORIZED, 40102)
            ErrorType.INVALID_REFRESH_TOKEN -> V1Error(HttpStatus.UNAUTHORIZED, 40103)
            ErrorType.MISSING_ACCESS_TOKEN -> V1Error(HttpStatus.UNAUTHORIZED, 0x2001)
            ErrorType.NOT_MY_EVALUATION -> V1Error(HttpStatus.FORBIDDEN, 40301)
            ErrorType.WRONG_API_KEY -> V1Error(HttpStatus.FORBIDDEN, 0x2000)
            ErrorType.INVALID_ACCESS_TOKEN -> V1Error(HttpStatus.FORBIDDEN, 0x2002)
            ErrorType.USER_NOT_ADMIN -> V1Error(HttpStatus.FORBIDDEN, 0x2003)
            ErrorType.UNREGISTERED_LOCAL_ID -> V1Error(HttpStatus.FORBIDDEN, 0x2004)
            ErrorType.PASSWORD_MISMATCH -> V1Error(HttpStatus.FORBIDDEN, 0x2005)
            ErrorType.INVALID_LOCAL_ID -> V1Error(HttpStatus.FORBIDDEN, 0x3000)
            ErrorType.INVALID_PASSWORD -> V1Error(HttpStatus.FORBIDDEN, 0x3001)
            ErrorType.DUPLICATE_LOCAL_ID -> V1Error(HttpStatus.FORBIDDEN, 0x3002)
            ErrorType.DUPLICATE_TIMETABLE_TITLE -> V1Error(HttpStatus.FORBIDDEN, 0x3003)
            ErrorType.DUPLICATE_LECTURE -> V1Error(HttpStatus.FORBIDDEN, 0x3004)
            ErrorType.LECTURE_SEMESTER_MISMATCH -> V1Error(HttpStatus.FORBIDDEN, 0x300A)
            ErrorType.INVALID_TIMETABLE_SEMESTER -> V1Error(HttpStatus.FORBIDDEN, 0x300B)
            ErrorType.LECTURE_TIME_OVERLAP -> V1Error(HttpStatus.FORBIDDEN, 0x300C)
            ErrorType.CANNOT_RESET_CUSTOM_LECTURE -> V1Error(HttpStatus.FORBIDDEN, 0x300D)
            ErrorType.INVALID_EMAIL -> V1Error(HttpStatus.FORBIDDEN, 0x300F)
            ErrorType.EMAIL_NOT_VERIFIED -> V1Error(HttpStatus.FORBIDDEN, 0x3011)
            ErrorType.TIMETABLE_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40400)
            ErrorType.PRIMARY_TIMETABLE_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40401)
            ErrorType.CONFIG_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40403)
            ErrorType.FRIEND_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40404)
            ErrorType.USER_NOT_FOUND_BY_NICKNAME -> V1Error(HttpStatus.NOT_FOUND, 40405)
            ErrorType.THEME_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40406)
            ErrorType.EVALUATION_TARGET_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40407)
            ErrorType.FRIEND_LINK_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40409)
            ErrorType.SOCIAL_PROVIDER_NOT_ATTACHED -> V1Error(HttpStatus.NOT_FOUND, 40410)
            ErrorType.DIARY_QUESTION_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40411)
            ErrorType.DIARY_DAILY_CLASS_TYPE_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40412)
            ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40413)
            ErrorType.DIARY_SUBMISSION_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40414)
            ErrorType.EVALUATION_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40415)
            ErrorType.COURSE_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40416)
            ErrorType.LECTURE_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 0x4003)
            ErrorType.USER_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 0x4004)
            ErrorType.TIMETABLE_LECTURE_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 0x4005)
            ErrorType.COURSEBOOK_NOT_FOUND -> V1Error(HttpStatus.NOT_FOUND, 40420)
            ErrorType.DUPLICATE_VACANCY_NOTIFICATION -> V1Error(HttpStatus.CONFLICT, 40900)
            ErrorType.DUPLICATE_EMAIL -> V1Error(HttpStatus.CONFLICT, 40901)
            ErrorType.DUPLICATE_FRIEND -> V1Error(HttpStatus.CONFLICT, 40902)
            ErrorType.SELF_FRIEND_REQUEST -> V1Error(HttpStatus.CONFLICT, 40903)
            ErrorType.NOT_CUSTOM_THEME -> V1Error(HttpStatus.CONFLICT, 40905)
            ErrorType.DUPLICATE_POPUP_KEY -> V1Error(HttpStatus.CONFLICT, 40906)
            ErrorType.ALREADY_DOWNLOADED_THEME -> V1Error(HttpStatus.CONFLICT, 40907)
            ErrorType.DUPLICATE_SOCIAL_ACCOUNT -> V1Error(HttpStatus.CONFLICT, 40908)
            ErrorType.CANNOT_REMOVE_LAST_AUTH_PROVIDER -> V1Error(HttpStatus.CONFLICT, 40909)
            ErrorType.DUPLICATE_EVALUATION -> V1Error(HttpStatus.CONFLICT, 40910)
            ErrorType.DUPLICATE_EVALUATION_REPORT -> V1Error(HttpStatus.CONFLICT, 40911)
            ErrorType.DUPLICATE_EVALUATION_LIKE -> V1Error(HttpStatus.CONFLICT, 40912)
            ErrorType.EVALUATION_NOT_LIKED -> V1Error(HttpStatus.CONFLICT, 40913)
            ErrorType.MY_EVALUATION_REPORT -> V1Error(HttpStatus.CONFLICT, 40914)
            ErrorType.EVALUATION_LECTURE_MISMATCH -> V1Error(HttpStatus.CONFLICT, 40915)
            ErrorType.DUPLICATE_NICKNAME -> V1Error(HttpStatus.CONFLICT, 40031)
            ErrorType.SOCIAL_PROVIDER_UNAVAILABLE -> V1Error(HttpStatus.BAD_GATEWAY, 50200)
            ErrorType.FEEDBACK_UPSTREAM_UNAVAILABLE -> V1Error(HttpStatus.BAD_GATEWAY, 50201)
        }

val ErrorType.v1Status: HttpStatus
    get() = v1.status

fun ErrorType.toV1ErrorResponse(displayMessage: String = this.displayMessage): ResponseEntity<V1ErrorResponse> =
    ResponseEntity
        .status(v1.status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            V1ErrorResponse(
                errcode = v1.errcode,
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
    fun handleSnuttException(e: SnuttException): ResponseEntity<V1ErrorResponse> = e.error.toV1ErrorResponse(e.message)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(): ResponseEntity<V1ErrorResponse> = ErrorType.INVALID_REQUEST_BODY.toV1ErrorResponse()

    @ExceptionHandler(MethodArgumentTypeMismatchException::class, MissingServletRequestParameterException::class)
    fun handleInvalidParameter(): ResponseEntity<V1ErrorResponse> = ErrorType.INVALID_PARAMETER.toV1ErrorResponse()

    @ExceptionHandler(UpstreamException::class)
    fun handleUpstreamException(e: UpstreamException): ResponseEntity<V1ErrorResponse> {
        log.error("upstream failure: provider={} error={}", e.provider, e.error, e)
        val legacy = if (e.error == ErrorType.SOCIAL_PROVIDER_UNAVAILABLE) ErrorType.SOCIAL_LOGIN_FAILED else e.error
        return legacy.toV1ErrorResponse()
    }
}
