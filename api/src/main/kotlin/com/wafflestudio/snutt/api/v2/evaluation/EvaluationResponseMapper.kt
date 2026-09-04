package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay

internal fun CursorPage<EvaluationDisplay>.toEvaluationResponsePage(): CursorPage<EvaluationResponse> = map { it.toEvaluationResponse() }

internal fun List<EvaluationDisplay>.toEvaluationResponses(): List<EvaluationResponse> = map { it.toEvaluationResponse() }

internal fun EvaluationDisplay.toEvaluationResponse(): EvaluationResponse {
    val evaluation = this.evaluation
    return EvaluationResponse(
        id = checkNotNull(evaluation.id),
        content = evaluation.content,
        gradeSatisfaction = evaluation.gradeSatisfaction,
        teachingSkill = evaluation.teachingSkill,
        gains = evaluation.gains,
        lifeBalance = evaluation.lifeBalance,
        rating = evaluation.rating,
        likeCount = evaluation.likeCount,
        isHidden = evaluation.isHidden,
        isReported = evaluation.isReported,
        isLiked = isLiked,
        fromSnuev = evaluation.fromSnuev,
        year = evaluation.year,
        semester = evaluation.semester,
        isModifiable = isModifiable,
        isReportable = isReportable,
    )
}
