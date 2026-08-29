package com.wafflestudio.snutt.api.v2.tag

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureVocabulary
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
    val sortCriteria: List<String>,
    val updatedAt: Long?,
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
    private val coursebookService: CoursebookService,
) {
    @GetMapping("/{year}/{semester}")
    fun getTagList(
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute clientInfo: ClientInfo,
    ): TagListResponse {
        val parsedSemester = Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val vocabulary = lectureVocabularyService.getVocabulary(year, parsedSemester, clientInfo.language)
        return TagListResponse(
            classification = vocabulary.classification,
            department = vocabulary.department,
            academicYear = vocabulary.academicYear,
            credit = vocabulary.credit,
            instructor = vocabulary.instructor,
            category = vocabulary.category,
            categoryPre2025 = vocabulary.categoryPre2025,
            sortCriteria = LectureSort.entries.filter { it != LectureSort.DEFAULT }.map { it.fullName },
            updatedAt = vocabulary.updatedAt?.toEpochMilli(),
        )
    }

    @GetMapping("/courses")
    fun getCourseTagList(
        @RequestAttribute clientInfo: ClientInfo,
    ): CourseTagListResponse {
        val vocabulary: LectureVocabulary = lectureVocabularyService.getVocabulary(null, null, clientInfo.language)
        return CourseTagListResponse(
            classification = vocabulary.classification,
            department = vocabulary.department,
            academicYear = vocabulary.academicYear,
            credit = vocabulary.credit,
            category = vocabulary.category,
            categoryPre2025 = vocabulary.categoryPre2025,
            semesters = coursebookService.getCoursebooks().map { SemesterResponse(it.year, it.semester) },
            updatedAt = vocabulary.updatedAt?.toEpochMilli(),
        )
    }
}
