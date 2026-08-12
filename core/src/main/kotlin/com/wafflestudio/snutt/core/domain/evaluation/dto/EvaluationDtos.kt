package com.wafflestudio.snutt.core.domain.evaluation.dto

data class EvaluationCursor(
    val year: Int,
    val semester: Int,
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
