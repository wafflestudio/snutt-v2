package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.UserService

internal fun CursorPage<EvaluationDisplay>.toEvaluationResponsePage(userService: UserService): CursorPage<EvaluationResponse> =
    CursorPage(
        content = content.toEvaluationResponses(userService),
        cursor = cursor,
        size = size,
        last = last,
        totalCount = totalCount,
    )

internal fun List<EvaluationDisplay>.toEvaluationResponses(userService: UserService): List<EvaluationResponse> {
    val userMap = userService.getAllByIds(mapNotNull { it.evaluation.userId })
    return map { it.toEvaluationResponse(userMap) }
}

internal fun EvaluationDisplay.toEvaluationResponse(userService: UserService): EvaluationResponse =
    toEvaluationResponse(userService.getAllByIds(listOfNotNull(evaluation.userId)))

internal fun EvaluationDisplay.toEvaluationResponse(userMap: Map<Long, User>): EvaluationResponse {
    val evaluation = this.evaluation
    return EvaluationResponse(
        id = checkNotNull(evaluation.id),
        user =
            evaluation.userId?.let { userId ->
                userMap[userId]?.let {
                    EvaluationUserResponse(id = it.id!!, nickname = it.nicknameWithoutTag)
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
