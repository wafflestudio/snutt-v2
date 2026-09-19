package com.wafflestudio.snutt.api.v2.tag

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseVocabularyRepository
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.service.LectureVocabularyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TagListResponse(
    val classification: List<String>,
    val department: List<String>,
    val academicYear: List<String>,
    val credit: List<Int>,
    val instructor: List<String>,
    val category: List<String>,
    val categoryPre2025: List<String>,
    val sortCriteria: List<SortCriterionResponse>,
    val updatedAt: Long?,
)

data class SortCriterionResponse(
    val value: String,
    val label: String,
)

data class SemesterResponse(
    val year: Int,
    val semester: Semester,
)

data class CourseTagListResponse(
    val classification: List<String>,
    val department: List<String>,
    val academicYear: List<String>,
    val credit: List<Int>,
    val category: List<String>,
    val categoryPre2025: List<String>,
    val semesters: List<SemesterResponse>,
    val updatedAt: Long?,
)

@RestController
@RequestMapping("/v2/tags")
class TagController(
    private val lectureVocabularyService: LectureVocabularyService,
    private val courseVocabularyRepository: CourseVocabularyRepository,
) {
    @GetMapping("/{year}/{semester}")
    fun getTagList(
        @PathVariable year: Int,
        @PathVariable semester: Semester,
        @RequestAttribute clientInfo: ClientInfo,
    ): TagListResponse {
        val vocabulary = lectureVocabularyService.getVocabulary(year, semester, clientInfo.language)
        return TagListResponse(
            classification = vocabulary.classification,
            department = vocabulary.department,
            academicYear = vocabulary.academicYear,
            credit = vocabulary.credit,
            instructor = vocabulary.instructor,
            category = vocabulary.category,
            categoryPre2025 =
                vocabulary.categoryPre2025.map {
                    LectureCategoryPre2025.localize(it, clientInfo.language)
                },
            sortCriteria =
                LectureSort.entries
                    .filter { it != LectureSort.DEFAULT }
                    .map { SortCriterionResponse(it.name.lowercase(), clientInfo.language.select(it.fullName, it.fullNameEn)) },
            updatedAt = vocabulary.updatedAt?.toEpochMilli(),
        )
    }

    @GetMapping("/courses")
    fun getCourseTagList(
        @RequestAttribute clientInfo: ClientInfo,
    ): CourseTagListResponse {
        val vocabulary = courseVocabularyRepository.getVocabulary(clientInfo.language)
        return CourseTagListResponse(
            classification = vocabulary.classification,
            department = vocabulary.department,
            academicYear = vocabulary.academicYear,
            credit = vocabulary.credit,
            category = vocabulary.category,
            categoryPre2025 =
                vocabulary.categoryPre2025.map {
                    LectureCategoryPre2025.localize(it, clientInfo.language)
                },
            semesters = vocabulary.semesters.map { SemesterResponse(it.year, it.semester) },
            updatedAt = vocabulary.updatedAt?.toEpochMilli(),
        )
    }
}
