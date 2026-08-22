package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.v1compat.error.toV1ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = ["com.wafflestudio.snutt.v1compat.ev"])
class V1CompatEvExceptionHandler {
    @ExceptionHandler(SnuttException::class)
    fun handleSnuttException(e: SnuttException): ResponseEntity<*> {
        val evCode = EV_ERROR_CODE_MAP[e.error] ?: return e.toV1ErrorResponse()
        return ResponseEntity
            .status(e.error.httpStatus)
            .body(mapOf("error" to mapOf("code" to evCode, "message" to e.errorMessage)))
    }

    companion object {
        private val EV_ERROR_CODE_MAP =
            mapOf(
                ErrorType.EVALUATION_CONTENT_BLANK to 20004,
                ErrorType.NOT_MY_EVALUATION to 23001,
                ErrorType.LECTURE_NOT_FOUND to 24001,
                ErrorType.EV_DATA_NOT_FOUND to 24001,
                ErrorType.COURSE_NOT_FOUND to 24002,
                ErrorType.EVALUATION_NOT_FOUND to 24005,
                ErrorType.DUPLICATE_EVALUATION to 29001,
                ErrorType.MY_EVALUATION_REPORT to 29002,
                ErrorType.DUPLICATE_EVALUATION_REPORT to 29003,
                ErrorType.DUPLICATE_EVALUATION_LIKE to 29004,
                ErrorType.EVALUATION_LIKE_NOT_FOUND to 29005,
                ErrorType.EVALUATION_LECTURE_MISMATCH to 29006,
            )
    }
}
