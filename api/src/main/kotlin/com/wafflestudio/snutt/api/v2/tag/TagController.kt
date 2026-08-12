package com.wafflestudio.snutt.api.v2.tag

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.tag.service.TagListService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
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

@RestController
@RequestMapping("/v2/tags")
class TagController(
    private val tagListService: TagListService,
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
}
