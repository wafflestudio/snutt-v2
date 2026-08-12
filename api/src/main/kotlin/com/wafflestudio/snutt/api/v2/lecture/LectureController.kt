package com.wafflestudio.snutt.api.v2.lecture

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LectureSearchRequest(
    val year: Int,
    val semester: Int,
    val query: String? = null,
    val classification: List<String>? = null,
    val credit: List<Int>? = null,
    val courseNumber: List<String>? = null,
    val academicYear: List<String>? = null,
    val department: List<String>? = null,
    val category: List<String>? = null,
    val categoryPre2025: List<String>? = null,
    val etcTags: List<String>? = null,
    val times: List<SearchTimeRequest>? = null,
    val timesToExclude: List<SearchTimeRequest>? = null,
    val page: Int = 0,
    val limit: Int = 20,
    val sortBy: String? = null,
)

data class SearchTimeRequest(
    val day: Int,
    val startMinute: Int,
    val endMinute: Int,
)

data class LectureResponse(
    val id: String,
    val year: Int,
    val semester: Semester,
    val courseNumber: String,
    val lectureNumber: String,
    val courseTitle: String,
    val instructor: String?,
    val department: String?,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val credit: Int,
    val quota: Int,
    val freshmanQuota: Int?,
    val remark: String?,
    val registrationCount: Int,
    val wasFull: Boolean,
    val classPlaceAndTime: List<ClassPlaceAndTimeResponse>,
)

data class ClassPlaceAndTimeResponse(
    val day: DayOfWeek,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
)

private fun Lecture.toResponse() =
    LectureResponse(
        id = externalId,
        year = year,
        semester = semester,
        courseNumber = courseNumber,
        lectureNumber = lectureNumber,
        courseTitle = courseTitle,
        instructor = instructor,
        department = department,
        academicYear = academicYear,
        category = category,
        categoryPre2025 = categoryPre2025,
        classification = classification,
        credit = credit,
        quota = quota,
        freshmanQuota = freshmanQuota,
        remark = remark,
        registrationCount = registrationCount,
        wasFull = wasFull,
        classPlaceAndTime = classPlaceAndTime.map { it.toResponse() },
    )

private fun ClassPlaceAndTime.toResponse() =
    ClassPlaceAndTimeResponse(
        day = day,
        place = place,
        startMinute = startMinute,
        endMinute = endMinute,
    )

@RestController
@RequestMapping("/v2/lectures")
class LectureController(
    private val lectureService: LectureService,
) {
    @Public
    @PostMapping("/search")
    fun searchLectures(
        @RequestBody request: LectureSearchRequest,
    ): List<LectureResponse> {
        val criteria =
            LectureSearchCriteria(
                year = request.year,
                semester = parseSemester(request.semester),
                query = request.query,
                classification = request.classification,
                credit = request.credit,
                courseNumber = request.courseNumber,
                academicYear = request.academicYear,
                department = request.department,
                category = request.category,
                categoryPre2025 = request.categoryPre2025,
                etcTags = request.etcTags,
                times = request.times?.map { parseSearchTime(it) },
                timesToExclude = request.timesToExclude?.map { parseSearchTime(it) },
                offset = request.page * request.limit.toLong(),
                limit = request.limit,
                sort = LectureSort.getOfName(request.sortBy) ?: LectureSort.DEFAULT,
            )
        return lectureService.search(criteria).map { it.toResponse() }
    }

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)

    private fun parseSearchTime(time: SearchTimeRequest): SearchTime {
        val day =
            DayOfWeek.getOfValue(time.day) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        return SearchTime(day = day, startMinute = time.startMinute, endMinute = time.endMinute)
    }
}
