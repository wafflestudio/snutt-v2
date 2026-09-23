package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationLike
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface EvaluationLikeRepository : JpaRepository<EvaluationLike, Long> {
    fun existsByEvaluationIdAndUserId(
        evaluationId: Long,
        userId: Long,
    ): Boolean

    fun findByUserIdAndEvaluationIdIn(
        userId: Long,
        evaluationIds: Collection<Long>,
    ): List<EvaluationLike>

    fun deleteByEvaluationIdAndUserId(
        evaluationId: Long,
        userId: Long,
    ): Int

    @Modifying
    @Query("DELETE FROM EvaluationLike l WHERE l.evaluationId = :evaluationId")
    fun deleteByEvaluationId(evaluationId: Long)
}
