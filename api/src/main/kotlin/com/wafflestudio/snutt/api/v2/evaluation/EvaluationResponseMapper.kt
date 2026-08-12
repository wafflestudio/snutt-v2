package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository

internal fun CursorPage<EvaluationDisplay>.toEvaluationResponsePage(userRepository: UserRepository): CursorPage<EvaluationResponse> =
    CursorPage(
        content = content.toEvaluationResponses(userRepository),
        cursor = cursor,
        size = size,
        last = last,
        totalCount = totalCount,
    )

internal fun List<EvaluationDisplay>.toEvaluationResponses(userRepository: UserRepository): List<EvaluationResponse> {
    val userMap =
        userRepository.findAllById(mapNotNull { it.evaluation.userId }).associateBy { it.id!! }
    return map { it.toEvaluationResponse(userMap) }
}

internal fun EvaluationDisplay.toEvaluationResponse(userRepository: UserRepository): EvaluationResponse {
    val userMap =
        evaluation.userId?.let { userRepository.findAllById(listOf(it)).associateBy { u -> u.id!! } }.orEmpty()
    return toEvaluationResponse(userMap)
}

internal fun EvaluationDisplay.toEvaluationResponse(userMap: Map<Long, User>): EvaluationResponse {
    val evaluation = this.evaluation
    return EvaluationResponse(
        id = checkNotNull(evaluation.id),
        user =
            evaluation.userId?.let { userId ->
                userMap[userId]?.let {
                    EvaluationUserResponse(id = it.externalId, nickname = it.nicknameWithoutTag)
                }
            },
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
