package com.wafflestudio.snutt.v1compat.ev

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationTag
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay
import io.swagger.v3.oas.annotations.media.Schema

data class LegacyEvTagGroupDto(
    val id: Int,
    val name: String,
    val ordering: Int,
    val color: String?,
    val tags: List<LegacyEvTagDto>,
)

data class LegacyEvTagDto(
    val id: Long,
    val name: String,
    val description: String?,
    val ordering: Int,
    @param:JsonProperty("key")
    @get:Schema(description = "고정 식별자. name은 표시용이라 바뀔 수 있다.")
    val key: String?,
)

data class LegacyEvaluationWithSemesterDto(
    val id: Long?,
    @param:JsonProperty("user_id")
    val userId: String?,
    val content: String,
    @param:JsonProperty("grade_satisfaction")
    val gradeSatisfaction: Double?,
    @param:JsonProperty("teaching_skill")
    val teachingSkill: Double?,
    val gains: Double?,
    @param:JsonProperty("life_balance")
    val lifeBalance: Double?,
    val rating: Double,
    @param:JsonProperty("like_count")
    val likeCount: Long,
    @param:JsonProperty("is_hidden")
    val isHidden: Boolean,
    @param:JsonProperty("is_reported")
    val isReported: Boolean,
    @param:JsonProperty("is_liked")
    val isLiked: Boolean,
    @param:JsonProperty("from_snuev")
    val fromSnuev: Boolean,
    val year: Int,
    val semester: Int,
    @param:JsonProperty("lecture_id")
    val lectureId: Long,
    @param:JsonProperty("is_modifiable")
    val isModifiable: Boolean,
    @param:JsonProperty("is_reportable")
    val isReportable: Boolean,
)

data class LegacyEvaluationWithLectureDto(
    val id: Long?,
    @param:JsonProperty("user_id")
    val userId: String?,
    val content: String,
    @param:JsonProperty("grade_satisfaction")
    val gradeSatisfaction: Double?,
    @param:JsonProperty("teaching_skill")
    val teachingSkill: Double?,
    val gains: Double?,
    @param:JsonProperty("life_balance")
    val lifeBalance: Double?,
    val rating: Double,
    @param:JsonProperty("like_count")
    val likeCount: Long,
    @param:JsonProperty("is_hidden")
    val isHidden: Boolean,
    @param:JsonProperty("is_reported")
    val isReported: Boolean,
    @param:JsonProperty("is_liked")
    val isLiked: Boolean,
    @param:JsonProperty("from_snuev")
    val fromSnuev: Boolean,
    val year: Int,
    val semester: Int,
    val lecture: LegacyEvaluationCourseDto?,
    @param:JsonProperty("is_modifiable")
    val isModifiable: Boolean,
    @param:JsonProperty("is_reportable")
    val isReportable: Boolean,
)

data class LegacyEvaluationCourseDto(
    val id: Long?,
    val title: String,
    val instructor: String,
)

data class LegacyEvaluationCreateResponse(
    val id: Long?,
    @param:JsonProperty("user_id")
    val userId: String?,
    val content: String,
    @param:JsonProperty("grade_satisfaction")
    val gradeSatisfaction: Double?,
    @param:JsonProperty("teaching_skill")
    val teachingSkill: Double?,
    val gains: Double?,
    @param:JsonProperty("life_balance")
    val lifeBalance: Double?,
    val rating: Double,
    @param:JsonProperty("like_count")
    val likeCount: Long,
    @param:JsonProperty("is_hidden")
    val isHidden: Boolean,
    @param:JsonProperty("is_reported")
    val isReported: Boolean,
    @param:JsonProperty("from_snuev")
    val fromSnuev: Boolean,
)

private class LegacyMainTag(
    val tag: EvaluationTag,
    val id: Long,
    val description: LocalizedMessage,
    val ordering: Int,
)

private val legacyMainTags: List<LegacyMainTag> =
    listOf(
        LegacyMainTag(EvaluationTag.RECENT, 1, LocalizedMessage(ko = "최근 등록된 강의평", en = "Recently posted reviews"), 1),
        LegacyMainTag(
            EvaluationTag.LIBERAL_EDUCATION,
            317,
            LocalizedMessage(ko = "전체 교양 강의평 모음", en = "All liberal arts course reviews"),
            2,
        ),
        LegacyMainTag(EvaluationTag.RECOMMENDED, 2, LocalizedMessage(ko = "학우들의 추천 강의", en = "Courses recommended by fellow students"), 3),
        LegacyMainTag(
            EvaluationTag.WELL_TAUGHT,
            3,
            LocalizedMessage(ko = "졸업하기 전에 꼭 한 번 들어볼 만한 강의", en = "Courses worth taking before you graduate"),
            4,
        ),
        LegacyMainTag(
            EvaluationTag.SWEET,
            4,
            LocalizedMessage(ko = "수업 부담이 크지 않고, 성적도 잘 주는 강의", en = "Light workload and generous grading"),
            5,
        ),
        LegacyMainTag(
            EvaluationTag.HARD_BUT_WORTH,
            5,
            LocalizedMessage(ko = "공과 시간을 들인 만큼 거두는 것이 많은 강의", en = "Courses that reward the time and effort you put in"),
            6,
        ),
    )

internal fun legacyMainTagGroup(language: Language): LegacyEvTagGroupDto =
    LegacyEvTagGroupDto(
        id = 1,
        name = "main",
        ordering = -1,
        color = null,
        tags =
            legacyMainTags.map {
                LegacyEvTagDto(
                    id = it.id,
                    name = it.tag.title.of(language),
                    description = it.description.of(language),
                    ordering = it.ordering,
                    key = it.tag.name,
                )
            },
    )

internal fun evaluationTagOfLegacyId(tagId: Long): EvaluationTag? = legacyMainTags.firstOrNull { it.id == tagId }?.tag

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

data class LegacyEvCursorPage<T>(
    val content: List<T>,
    val cursor: String?,
    val size: Int,
    val last: Boolean,
    @param:JsonProperty("total_count")
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
