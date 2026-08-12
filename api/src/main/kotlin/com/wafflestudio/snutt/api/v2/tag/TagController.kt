package com.wafflestudio.snutt.api.v2.tag

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.auth.EmailVerifiedRequired
import com.wafflestudio.snutt.api.v2.evaluation.EvaluationResponse
import com.wafflestudio.snutt.api.v2.evaluation.toEvaluationResponsePage
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.tag.service.TagGroupDisplay
import com.wafflestudio.snutt.core.domain.tag.service.TagService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class TagListResponse(
    val classification: List<String>,
    val department: List<String>,
    val academicYear: List<String>,
    val credit: List<String>,
    val instructor: List<String>,
    val category: List<String>,
    val categoryPre2025: List<String>,
    val sortCriteria: List<String>,
    val updatedAt: Long,
)

data class TagGroupResponse(
    val id: Long,
    val name: String,
    val ordering: Int,
    val color: String?,
    val tags: List<TagResponse>,
)

data class TagResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val ordering: Int,
)

internal fun TagGroupDisplay.toResponse() =
    TagGroupResponse(
        id = id,
        name = name,
        ordering = ordering,
        color = color,
        tags = tags.map { TagResponse(it.id, it.name, it.description, it.ordering) },
    )

@RestController
@RequestMapping("/v2/tags")
class TagController(
    private val tagListService: com.wafflestudio.snutt.core.domain.tag.service.TagListService,
    private val tagService: TagService,
    private val evaluationService: EvaluationService,
    private val userRepository: UserRepository,
) {
    @GetMapping("/{year}/{semester}")
    fun getTagList(
        @PathVariable year: Int,
        @PathVariable semester: Int,
    ): TagListResponse {
        val tagList =
            tagListService.getTagList(
                year,
                Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
            )
        return TagListResponse(
            classification = tagList.tagCollection.classification,
            department = tagList.tagCollection.department,
            academicYear = tagList.tagCollection.academicYear,
            credit = tagList.tagCollection.credit,
            instructor = tagList.tagCollection.instructor,
            category = tagList.tagCollection.category,
            categoryPre2025 = tagList.tagCollection.categoryPre2025,
            sortCriteria = LectureSort.entries.filter { it != LectureSort.DEFAULT }.map { it.fullName },
            updatedAt = checkNotNull(tagList.updatedAt).toEpochMilli(),
        )
    }

    // ev 강의평 태그 (v1 ev-service 게이트 이식: 이메일 인증 필수)
    @EmailVerifiedRequired
    @GetMapping("/main")
    fun getMainTags(
        @CurrentUser user: User,
    ): TagGroupResponse = tagService.getMainTags().toResponse()

    @EmailVerifiedRequired
    @GetMapping("/search")
    fun getSearchTags(
        @CurrentUser user: User,
    ): List<TagGroupResponse> = tagService.getSearchTags().map { it.toResponse() }

    @EmailVerifiedRequired
    @GetMapping("/main/{tagId}/evaluations")
    fun getMainTagEvaluations(
        @CurrentUser user: User,
        @PathVariable tagId: Long,
        @RequestParam(required = false) cursor: String?,
    ): CursorPage<EvaluationResponse> =
        evaluationService.getEvaluationsByTag(user.id!!, tagId, cursor).toEvaluationResponsePage(userRepository)
}
