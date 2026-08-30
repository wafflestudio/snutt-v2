package com.wafflestudio.snutt.core.domain.evaluation.dto

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException

enum class EvaluationSort {
    LATEST,
    RECOMMENDED,
    ;

    companion object {
        fun fromParameter(value: String?): EvaluationSort =
            when (value?.lowercase()) {
                null, "", "latest" -> LATEST
                "recommended" -> RECOMMENDED
                else -> throw SnuttException(ErrorType.INVALID_EVALUATION_SORT)
            }
    }
}
