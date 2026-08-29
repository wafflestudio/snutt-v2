package com.wafflestudio.snutt.core.domain.evaluation.dto

data class EvaluationCursor(
    val version: Int,
    val sort: EvaluationSort,
    val year: Int,
    val semester: Int,
    val evaluationId: Long,
    val likeCount: Long? = null,
)

data class EvaluationIdCursor(
    val version: Int,
    val evaluationId: Long,
)

data class EvaluationSummary(
    val avgRating: Double?,
    val evalCount: Long,
)

data class EvaluationAverages(
    val avgGradeSatisfaction: Double?,
    val avgTeachingSkill: Double?,
    val avgGains: Double?,
    val avgLifeBalance: Double?,
    val avgRating: Double?,
)
