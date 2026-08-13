package com.wafflestudio.snutt.api.v1compat.ev

import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay

// v1 태그 그룹 형태. 태그 id는 목록 순번이다
internal fun legacyMainTagGroup(): Map<String, Any?> =
    linkedMapOf(
        "id" to 1,
        "name" to "main",
        "ordering" to 1,
        "color" to null,
        "tags" to
            EvaluationTag.entries.mapIndexed { index, tag ->
                linkedMapOf(
                    "id" to index + 1,
                    "name" to tag.title,
                    "description" to tag.description,
                    "ordering" to index + 1,
                )
            },
    )

internal fun evaluationTagOfLegacyId(tagId: Long): EvaluationTag? = EvaluationTag.entries.getOrNull((tagId - 1).toInt())

// v1 ev 응답 형태 (../snutt-ev/core/.../evaluation/dto/EvaluationResponse.kt)
// userId는 v2 내부 Long에서 공개 id(hex) 문자열로 바꾼다. lectureId는 재채번된 course id다.
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

// v1 LectureEvaluationDto (생성 응답) — 사용자/수강 정보가 없는 단순 형태
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
