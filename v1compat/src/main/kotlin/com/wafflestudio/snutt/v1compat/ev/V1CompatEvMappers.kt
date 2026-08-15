package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay

private val EvaluationTag.legacyId: Long
    get() =
        when (this) {
            EvaluationTag.RECENT -> 1L
            EvaluationTag.LIBERAL_EDUCATION -> 317L
            EvaluationTag.RECOMMENDED -> 2L
            EvaluationTag.WELL_TAUGHT -> 3L
            EvaluationTag.SWEET -> 4L
            EvaluationTag.HARD_BUT_WORTH -> 5L
        }

internal fun legacyMainTagGroup(): Map<String, Any?> =
    linkedMapOf(
        "id" to 1,
        "name" to "main",
        "ordering" to -1,
        "color" to null,
        "tags" to
            EvaluationTag.entries.mapIndexed { index, tag ->
                linkedMapOf(
                    "id" to tag.legacyId,
                    "name" to tag.title,
                    "description" to tag.description,
                    "ordering" to index + 1,
                )
            },
    )

internal fun evaluationTagOfLegacyId(tagId: Long): EvaluationTag? = EvaluationTag.entries.firstOrNull { it.legacyId == tagId }

internal fun EvaluationDisplay.toLegacyWithSemester(userExternalIds: Map<Long, String>): Map<String, Any?> {
    val e = evaluation
    return linkedMapOf(
        "id" to e.id,
        "userId" to e.userId?.let(userExternalIds::get),
        "content" to e.content,
        "gradeSatisfaction" to e.gradeSatisfaction,
        "teachingSkill" to e.teachingSkill,
        "gains" to e.gains,
        "lifeBalance" to e.lifeBalance,
        "rating" to e.rating,
        "likeCount" to e.likeCount,
        "isHidden" to e.isHidden,
        "isReported" to e.isReported,
        "isLiked" to isLiked,
        "fromSnuev" to e.fromSnuev,
        "year" to e.year,
        "semester" to e.semester.value,
        "lectureId" to e.courseId,
        "isModifiable" to isModifiable,
        "isReportable" to isReportable,
    )
}

internal fun EvaluationDisplay.toLegacyWithLecture(
    userExternalIds: Map<Long, String>,
    courseMap: Map<Long, Course>,
): Map<String, Any?> {
    val e = evaluation
    return linkedMapOf(
        "id" to e.id,
        "userId" to e.userId?.let(userExternalIds::get),
        "content" to e.content,
        "gradeSatisfaction" to e.gradeSatisfaction,
        "teachingSkill" to e.teachingSkill,
        "gains" to e.gains,
        "lifeBalance" to e.lifeBalance,
        "rating" to e.rating,
        "likeCount" to e.likeCount,
        "isHidden" to e.isHidden,
        "isReported" to e.isReported,
        "isLiked" to isLiked,
        "fromSnuev" to e.fromSnuev,
        "year" to e.year,
        "semester" to e.semester.value,
        "lecture" to
            courseMap[e.courseId]?.let {
                linkedMapOf("id" to it.id, "title" to it.title, "instructor" to it.instructor)
            },
        "isModifiable" to isModifiable,
        "isReportable" to isReportable,
    )
}

internal fun EvaluationDisplay.toLegacyCreate(userExternalIds: Map<Long, String>): Map<String, Any?> {
    val e = evaluation
    return linkedMapOf(
        "id" to e.id,
        "userId" to e.userId?.let(userExternalIds::get),
        "content" to e.content,
        "gradeSatisfaction" to e.gradeSatisfaction,
        "teachingSkill" to e.teachingSkill,
        "gains" to e.gains,
        "lifeBalance" to e.lifeBalance,
        "rating" to e.rating,
        "likeCount" to e.likeCount,
        "isHidden" to e.isHidden,
        "isReported" to e.isReported,
        "fromSnuev" to e.fromSnuev,
    )
}
