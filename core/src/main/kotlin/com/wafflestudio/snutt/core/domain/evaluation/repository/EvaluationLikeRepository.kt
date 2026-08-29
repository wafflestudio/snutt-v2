package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationLike
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface EvaluationLikeRepository : JpaRepository<EvaluationLike, Long> {
    fun existsByEvaluationIdAndUserId(
        evaluationId: Long,
        userId: Long,
    ): Boolean

    @Query("SELECT l.evaluationId FROM EvaluationLike l WHERE l.userId = :userId AND l.evaluationId IN :evaluationIds")
    fun findLikedEvaluationIds(
        userId: Long,
        evaluationIds: Collection<Long>,
    ): List<Long>

    fun deleteByEvaluationIdAndUserId(
        evaluationId: Long,
        userId: Long,
    ): Int

    fun deleteByEvaluationId(evaluationId: Long)
}
