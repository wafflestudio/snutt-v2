package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

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

private val legacyMainTags: List<Pair<EvaluationTag, LegacyEvTagDto>> =
    listOf(
        EvaluationTag.RECENT to LegacyEvTagDto(id = 1, name = "최신", description = "최근 등록된 강의평", ordering = 1),
        EvaluationTag.LIBERAL_EDUCATION to LegacyEvTagDto(id = 317, name = "교양", description = "전체 교양 강의평 모음", ordering = 2),
        EvaluationTag.RECOMMENDED to LegacyEvTagDto(id = 2, name = "추천", description = "학우들의 추천 강의", ordering = 3),
        EvaluationTag.WELL_TAUGHT to
            LegacyEvTagDto(id = 3, name = "명강", description = "졸업하기 전에 꼭 한 번 들어볼 만한 강의", ordering = 4),
        EvaluationTag.SWEET to LegacyEvTagDto(id = 4, name = "꿀강", description = "수업 부담이 크지 않고, 성적도 잘 주는 강의", ordering = 5),
        EvaluationTag.HARD_BUT_WORTH to
            LegacyEvTagDto(id = 5, name = "고진감래", description = "공과 시간을 들인 만큼 거두는 것이 많은 강의", ordering = 6),
    )

internal fun legacyMainTagGroup(): LegacyEvTagGroupDto =
    LegacyEvTagGroupDto(
        id = 1,
        name = "main",
        ordering = -1,
        color = null,
        tags = legacyMainTags.map { it.second },
    )

internal fun evaluationTagOfLegacyId(tagId: Long): EvaluationTag? = legacyMainTags.firstOrNull { it.second.id == tagId }?.first

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

internal fun EvaluationDisplay.toLegacyWithLecture(courseMap: Map<Long, LegacyCourseMetadata>): LegacyEvaluationWithLectureDto {
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
