package com.wafflestudio.snutt.api.v1compat.ev

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// v1 ev-service는 ev 에러 봉투 {error: {code, message}}를 그대로 노출했다 (PLAN.md §3).
// 평가 도메인 에러만 ev 코드로 변환하고, 나머지(이메일 게이트 등)는 snutt 봉투로 내려간다
@RestControllerAdvice(basePackages = ["com.wafflestudio.snutt.api.v1compat.ev"])
class V1CompatEvExceptionHandler {
    @ExceptionHandler(SnuttException::class)
    fun handleSnuttException(e: SnuttException): ResponseEntity<Map<String, Any?>>? {
        val evCode = EV_ERROR_CODE_MAP[e.error] ?: return null
        return ResponseEntity
            .status(e.error.httpStatus)
            .body(mapOf("error" to mapOf("code" to evCode, "message" to e.errorMessage)))
    }

    companion object {
        // ev ErrorType 코드 (../snutt-ev/core/.../ErrorType.kt)
        private val EV_ERROR_CODE_MAP =
            mapOf(
                ErrorType.EVALUATION_CONTENT_BLANK to 20004,
                ErrorType.NOT_MY_EVALUATION to 23001,
                ErrorType.LECTURE_NOT_FOUND to 24001,
                ErrorType.EV_DATA_NOT_FOUND to 24001,
                ErrorType.TAG_GROUP_NOT_FOUND to 24003,
                ErrorType.TAG_NOT_FOUND to 24004,
                ErrorType.EVALUATION_NOT_FOUND to 24005,
                ErrorType.DUPLICATE_EVALUATION to 29001,
                ErrorType.MY_EVALUATION_REPORT to 29002,
                ErrorType.DUPLICATE_EVALUATION_REPORT to 29003,
                ErrorType.DUPLICATE_EVALUATION_LIKE to 29004,
                ErrorType.EVALUATION_LIKE_NOT_FOUND to 29005,
            )
    }
}
