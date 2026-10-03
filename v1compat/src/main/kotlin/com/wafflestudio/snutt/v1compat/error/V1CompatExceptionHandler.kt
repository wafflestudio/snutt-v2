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

private val V1_ERROR_CODE_MAP =
    mapOf(
        ErrorType.INVALID_TIMETABLE_TITLE to 0x1007,
        ErrorType.INVALID_CLASS_TIME to 0x100C,
        ErrorType.WRONG_API_KEY to 0x2000,
        ErrorType.MISSING_ACCESS_TOKEN to 0x2001,
        ErrorType.INVALID_ACCESS_TOKEN to 0x2002,
        ErrorType.USER_NOT_ADMIN to 0x2003,
        ErrorType.UNREGISTERED_LOCAL_ID to 0x2004,
        ErrorType.PASSWORD_MISMATCH to 0x2005,
        ErrorType.INVALID_LOCAL_ID to 0x3000,
        ErrorType.INVALID_PASSWORD to 0x3001,
        ErrorType.DUPLICATE_LOCAL_ID to 0x3002,
        ErrorType.DUPLICATE_TIMETABLE_TITLE to 0x3003,
        ErrorType.DUPLICATE_LECTURE to 0x3004,
        ErrorType.LECTURE_SEMESTER_MISMATCH to 0x300A,
        ErrorType.INVALID_TIMETABLE_SEMESTER to 0x300B,
        ErrorType.LECTURE_TIME_OVERLAP to 0x300C,
        ErrorType.CANNOT_RESET_CUSTOM_LECTURE to 0x300D,
        ErrorType.INVALID_EMAIL to 0x300F,
        ErrorType.EMAIL_NOT_VERIFIED to 0x3011,
        ErrorType.LECTURE_NOT_FOUND to 0x4003,
        ErrorType.USER_NOT_FOUND to 0x4004,
        ErrorType.TIMETABLE_LECTURE_NOT_FOUND to 0x4005,
        ErrorType.DIARY_SUBMISSION_TOO_FREQUENT to 40028L,
        ErrorType.DUPLICATE_NICKNAME to 40031L,
    )

private val ErrorType.v1ErrorCode: Long
    get() =
        when (this) {
            ErrorType.INVALID_PARAMETER -> 40001
            ErrorType.INVALID_REQUEST_BODY -> 40002
            ErrorType.INVALID_REGISTRATION_FOR_PREVIOUS_SEMESTER_COURSE -> 40005
            ErrorType.INVALID_NICKNAME -> 40008
            ErrorType.INVALID_DISPLAY_NAME -> 40009
            ErrorType.CANNOT_DELETE_LAST_TIMETABLE -> 40010
            ErrorType.INVALID_THEME_COLOR_COUNT -> 40012
            ErrorType.NOT_DEFAULT_THEME -> 40014
            ErrorType.TOO_MANY_FILES -> 40015
            ErrorType.EMAIL_ALREADY_VERIFIED -> 40016
            ErrorType.TOO_MANY_VERIFICATION_CODE_REQUESTS -> 40017
            ErrorType.INVALID_VERIFICATION_CODE -> 40018
            ErrorType.ALREADY_LOCAL_ACCOUNT -> 40019
            ErrorType.ALREADY_SOCIAL_ACCOUNT -> 40020
            ErrorType.NOT_PUBLISHED_THEME -> 40022
            ErrorType.TIMETABLE_LECTURE_REMINDER_INVALID_TIME -> 40024
            ErrorType.INVALID_DIARY_QUESTION -> 40026
            ErrorType.DIARY_COMMENT_TOO_LONG -> 40027
            ErrorType.EVALUATION_CONTENT_BLANK -> 40028
            ErrorType.EVALUATION_RATING_OUT_OF_RANGE -> 40029
            ErrorType.EVALUATION_REPORT_CONTENT_BLANK -> 40030
            ErrorType.INVALID_TIMETABLE_TITLE -> 40032
            ErrorType.INVALID_CLASS_TIME -> 40033
            ErrorType.INVALID_EVALUATION_SORT -> 40034
            ErrorType.INVALID_CURSOR -> 40035
            ErrorType.DIARY_SUBMISSION_TOO_FREQUENT -> 40036
            ErrorType.SOCIAL_LOGIN_FAILED -> 40100
            ErrorType.INVALID_APPLE_LOGIN_TOKEN -> 40101
            ErrorType.EXPIRED_ACCESS_TOKEN -> 40102
            ErrorType.INVALID_REFRESH_TOKEN -> 40103
            ErrorType.MISSING_ACCESS_TOKEN -> 40104
            ErrorType.NOT_MY_EVALUATION -> 40301
            ErrorType.WRONG_API_KEY -> 40302
            ErrorType.INVALID_ACCESS_TOKEN -> 40303
            ErrorType.USER_NOT_ADMIN -> 40304
            ErrorType.UNREGISTERED_LOCAL_ID -> 40305
            ErrorType.PASSWORD_MISMATCH -> 40306
            ErrorType.INVALID_LOCAL_ID -> 40307
            ErrorType.INVALID_PASSWORD -> 40308
            ErrorType.DUPLICATE_LOCAL_ID -> 40309
            ErrorType.DUPLICATE_TIMETABLE_TITLE -> 40310
            ErrorType.DUPLICATE_LECTURE -> 40311
            ErrorType.LECTURE_SEMESTER_MISMATCH -> 40312
            ErrorType.INVALID_TIMETABLE_SEMESTER -> 40313
            ErrorType.LECTURE_TIME_OVERLAP -> 40314
            ErrorType.CANNOT_RESET_CUSTOM_LECTURE -> 40315
            ErrorType.INVALID_EMAIL -> 40316
            ErrorType.EMAIL_NOT_VERIFIED -> 40317
            ErrorType.TIMETABLE_NOT_FOUND -> 40400
            ErrorType.PRIMARY_TIMETABLE_NOT_FOUND -> 40401
            ErrorType.CONFIG_NOT_FOUND -> 40403
            ErrorType.FRIEND_NOT_FOUND -> 40404
            ErrorType.USER_NOT_FOUND_BY_NICKNAME -> 40405
            ErrorType.THEME_NOT_FOUND -> 40406
            ErrorType.EVALUATION_TARGET_NOT_FOUND -> 40407
            ErrorType.FRIEND_LINK_NOT_FOUND -> 40409
            ErrorType.SOCIAL_PROVIDER_NOT_ATTACHED -> 40410
            ErrorType.DIARY_QUESTION_NOT_FOUND -> 40411
            ErrorType.DIARY_DAILY_CLASS_TYPE_NOT_FOUND -> 40412
            ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND -> 40413
            ErrorType.DIARY_SUBMISSION_NOT_FOUND -> 40414
            ErrorType.EVALUATION_NOT_FOUND -> 40415
            ErrorType.COURSE_NOT_FOUND -> 40416
            ErrorType.LECTURE_NOT_FOUND -> 40417
            ErrorType.USER_NOT_FOUND -> 40418
            ErrorType.TIMETABLE_LECTURE_NOT_FOUND -> 40419
            ErrorType.COURSEBOOK_NOT_FOUND -> 40420
            ErrorType.DUPLICATE_VACANCY_NOTIFICATION -> 40900
            ErrorType.DUPLICATE_EMAIL -> 40901
            ErrorType.DUPLICATE_FRIEND -> 40902
            ErrorType.SELF_FRIEND_REQUEST -> 40903
            ErrorType.NOT_CUSTOM_THEME -> 40905
            ErrorType.DUPLICATE_POPUP_KEY -> 40906
            ErrorType.ALREADY_DOWNLOADED_THEME -> 40907
            ErrorType.DUPLICATE_SOCIAL_ACCOUNT -> 40908
            ErrorType.CANNOT_REMOVE_LAST_AUTH_PROVIDER -> 40909
            ErrorType.DUPLICATE_EVALUATION -> 40910
            ErrorType.DUPLICATE_EVALUATION_REPORT -> 40911
            ErrorType.DUPLICATE_EVALUATION_LIKE -> 40912
            ErrorType.EVALUATION_NOT_LIKED -> 40913
            ErrorType.MY_EVALUATION_REPORT -> 40914
            ErrorType.EVALUATION_LECTURE_MISMATCH -> 40915
            ErrorType.DUPLICATE_NICKNAME -> 40916
            ErrorType.SOCIAL_PROVIDER_UNAVAILABLE -> 50200
            ErrorType.FEEDBACK_UPSTREAM_UNAVAILABLE -> 50201
        }

val ErrorType.v1Status: HttpStatus
    get() = HttpStatus.valueOf((v1ErrorCode / 100).toInt())

fun ErrorType.toV1ErrorResponse(displayMessage: String = this.displayMessage): ResponseEntity<V1ErrorResponse> =
    ResponseEntity
        .status(v1Status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            V1ErrorResponse(
                errcode = V1_ERROR_CODE_MAP[this] ?: v1ErrorCode,
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
