package com.wafflestudio.snutt.api.v2.tag

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.tag.service.TagListService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
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

/**
 * 학기별 검색 필터 어휘. 강의 검색과 강의평 과목 검색이 같은 목록을 쓴다.
 */
@RestController
@RequestMapping("/v2/tags")
class TagController(
    private val tagListService: TagListService,
) {
    @GetMapping("/{year}/{semester}")
    fun getTagList(
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute clientInfo: ClientInfo,
    ): TagListResponse {
        val tagList =
            tagListService.getTagList(
                year,
                Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
            )
        val collection = tagList.tagCollection

        // EN이면 영문 태그 우선, 비어있으면 한글 폴백
        fun localize(
            ko: List<String>,
            en: List<String>,
        ): List<String> = if (clientInfo.language == Language.EN) en.ifEmpty { ko } else ko
        return TagListResponse(
            classification = localize(collection.classification, collection.classificationEn),
            department = localize(collection.department, collection.departmentEn),
            academicYear = localize(collection.academicYear, collection.academicYearEn),
            credit = collection.credit,
            instructor = localize(collection.instructor, collection.instructorEn),
            category = localize(collection.category, collection.categoryEn),
            categoryPre2025 = collection.categoryPre2025,
            sortCriteria = LectureSort.entries.filter { it != LectureSort.DEFAULT }.map { it.fullName },
            updatedAt = checkNotNull(tagList.updatedAt).toEpochMilli(),
        )
    }
}
