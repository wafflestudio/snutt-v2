package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

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

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvTagGroupDto(
    val id: Int,
    val name: String,
    val ordering: Int,
    val color: String?,
    val tags: List<LegacyEvTagDto>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvTagDto(
    val id: Long,
    val name: String,
    val description: String?,
    val ordering: Int,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationWithSemesterDto(
    val id: Long?,
    val userId: String?,
    val content: String,
    val gradeSatisfaction: Double?,
    val teachingSkill: Double?,
    val gains: Double?,
    val lifeBalance: Double?,
    val rating: Double,
    val likeCount: Long,
    val isHidden: Boolean,
    val isReported: Boolean,
    val isLiked: Boolean,
    val fromSnuev: Boolean,
    val year: Int,
    val semester: Int,
    val lectureId: Long,
    val isModifiable: Boolean,
    val isReportable: Boolean,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationWithLectureDto(
    val id: Long?,
    val userId: String?,
    val content: String,
    val gradeSatisfaction: Double?,
    val teachingSkill: Double?,
    val gains: Double?,
    val lifeBalance: Double?,
    val rating: Double,
    val likeCount: Long,
    val isHidden: Boolean,
    val isReported: Boolean,
    val isLiked: Boolean,
    val fromSnuev: Boolean,
    val year: Int,
    val semester: Int,
    val lecture: LegacyEvaluationCourseDto?,
    val isModifiable: Boolean,
    val isReportable: Boolean,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationCourseDto(
    val id: Long?,
    val title: String,
    val instructor: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvaluationCreateResponse(
    val id: Long?,
    val userId: String?,
    val content: String,
    val gradeSatisfaction: Double?,
    val teachingSkill: Double?,
    val gains: Double?,
    val lifeBalance: Double?,
    val rating: Double,
    val likeCount: Long,
    val isHidden: Boolean,
    val isReported: Boolean,
    val fromSnuev: Boolean,
)

internal fun legacyMainTagGroup(): LegacyEvTagGroupDto =
    LegacyEvTagGroupDto(
        id = 1,
        name = "main",
        ordering = -1,
        color = null,
        tags =
            listOf(
                EvaluationTag.RECENT,
                EvaluationTag.RECOMMENDED,
                EvaluationTag.WELL_TAUGHT,
                EvaluationTag.SWEET,
                EvaluationTag.HARD_BUT_WORTH,
            ).mapIndexed { index, tag ->
                LegacyEvTagDto(
                    id = tag.legacyId,
                    name = tag.title,
                    description = tag.description,
                    ordering = index + 1,
                )
            },
    )

internal fun evaluationTagOfLegacyId(tagId: Long): EvaluationTag? = EvaluationTag.entries.firstOrNull { it.legacyId == tagId }

internal fun EvaluationDisplay.toLegacyWithSemester(): LegacyEvaluationWithSemesterDto {
    val e = evaluation
    return LegacyEvaluationWithSemesterDto(
        id = e.id,
        userId = e.userId?.toString(),
        content = e.content,
        gradeSatisfaction = e.gradeSatisfaction,
        teachingSkill = e.teachingSkill,
        gains = e.gains,
        lifeBalance = e.lifeBalance,
        rating = e.rating,
        likeCount = e.likeCount,
        isHidden = e.isHidden,
        isReported = e.isReported,
        isLiked = isLiked,
        fromSnuev = e.fromSnuev,
        year = e.year,
        semester = e.semester.value,
        lectureId = e.courseId,
        isModifiable = isModifiable,
        isReportable = isReportable,
    )
}

internal fun EvaluationDisplay.toLegacyWithLecture(courseMap: Map<Long, Course>): LegacyEvaluationWithLectureDto {
    val e = evaluation
    return LegacyEvaluationWithLectureDto(
        id = e.id,
        userId = e.userId?.toString(),
        content = e.content,
        gradeSatisfaction = e.gradeSatisfaction,
        teachingSkill = e.teachingSkill,
        gains = e.gains,
        lifeBalance = e.lifeBalance,
        rating = e.rating,
        likeCount = e.likeCount,
        isHidden = e.isHidden,
        isReported = e.isReported,
        isLiked = isLiked,
        fromSnuev = e.fromSnuev,
        year = e.year,
        semester = e.semester.value,
        lecture =
            courseMap[e.courseId]?.let {
                LegacyEvaluationCourseDto(id = it.id, title = it.title, instructor = it.instructor)
            },
        isModifiable = isModifiable,
        isReportable = isReportable,
    )
}

internal fun EvaluationDisplay.toLegacyCreate(): LegacyEvaluationCreateResponse {
    val e = evaluation
    return LegacyEvaluationCreateResponse(
        id = e.id,
        userId = e.userId?.toString(),
        content = e.content,
        gradeSatisfaction = e.gradeSatisfaction,
        teachingSkill = e.teachingSkill,
        gains = e.gains,
        lifeBalance = e.lifeBalance,
        rating = e.rating,
        likeCount = e.likeCount,
        isHidden = e.isHidden,
        isReported = e.isReported,
        fromSnuev = e.fromSnuev,
    )
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyEvCursorPage<T>(
    val content: List<T>,
    val cursor: String?,
    val size: Int,
    val last: Boolean,
    val totalCount: Long? = null,
)

internal fun <T, R> CursorPage<T>.toLegacyEvPage(transform: (T) -> R): LegacyEvCursorPage<R> =
    LegacyEvCursorPage(
        content = content.map(transform),
        cursor = cursor,
        size = size,
        last = last,
        totalCount = totalCount,
    )
