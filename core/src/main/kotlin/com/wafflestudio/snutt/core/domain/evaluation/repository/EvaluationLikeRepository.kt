package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationLike
import org.springframework.data.jpa.repository.JpaRepository

interface EvaluationLikeRepository : JpaRepository<EvaluationLike, Long> {
    fun existsByEvaluationIdAndUserId(
        evaluationId: Long,
        userId: Long,
    ): Boolean

    fun deleteByEvaluationIdAndUserId(
        evaluationId: Long,
        userId: Long,
    ): Int

    fun deleteByEvaluationId(evaluationId: Long)
}
