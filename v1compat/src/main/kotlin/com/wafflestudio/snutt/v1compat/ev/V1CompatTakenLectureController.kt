package com.wafflestudio.snutt.v1compat.ev

import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService
import com.wafflestudio.snutt.core.domain.evaluation.service.LectureTakenByUser
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyTakenLecturesResponse(
    val content: List<LegacyTakenLectureDto>,
    val totalCount: Int = content.size,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyTakenLectureDto(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    val courseNumber: String,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val takenYear: Int,
    val takenSemester: Int,
)

@RestController
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatTakenLectureController(
    private val takenLectureService: TakenLectureService,
) {
    // 구 백엔드는 클라이언트 요청을 가로채 서버 시간표에서 최근 2개 학기 강의를 조립해
    // snutt-ev에 전달했다. 클라이언트는 snutt_lecture_info를 보내지 않는다.
    @GetMapping("/users/me/lectures/latest")
    fun getMyLatestLectures(
        @V1CurrentUser user: User,
        @RequestParam(required = false) filter: String?,
    ): LegacyTakenLecturesResponse =
        LegacyTakenLecturesResponse(
            content =
                takenLectureService
                    .getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
                    .map { it.toLegacyTakenLecture() },
        )

    private fun LectureTakenByUser.toLegacyTakenLecture() =
        LegacyTakenLectureDto(
            id = course.id,
            title = course.title,
            instructor = course.instructor,
            department = course.department,
            courseNumber = course.courseNumber,
            credit = course.credit,
            academicYear = course.academicYear,
            category = course.category,
            classification = course.classification,
            takenYear = takenYear,
            takenSemester = takenSemester.value,
        )
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacySearchTagGroupsResponse(
    val tagGroups: List<LegacyEvTagGroupDto>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyCourseSearchResponse(
    val content: List<LegacyCourseDto>,
    val page: Int,
    val size: Int,
    val last: Boolean,
    val totalCount: Long,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyCourseDto(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    val courseNumber: String,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evaluation: LegacyCourseEvaluationSummaryDto,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyCourseEvaluationSummaryDto(
    val avgRating: Double?,
    val evaluationCount: Long,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacyCourseWithSemestersResponse(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    val courseNumber: String,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val semesterLectures: List<LegacySemesterLectureDto>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class LegacySemesterLectureDto(
    val id: Long,
    val year: Int,
    val semester: Int,
    val credit: Int,
    val extraInfo: String,
    val academicYear: String,
    val category: String,
    val classification: String,
    val myEvaluationExists: Boolean,
)

private const val LEGACY_COURSE_PAGE_SIZE = 20

@RestController
@RequestMapping("/v1/ev-service/v1", "/v1/ev/v1")
class V1CompatCourseSearchController(
    private val courseSearchService: CourseSearchService,
    private val legacySearchTagService: LegacySearchTagService,
    private val lectureService: LectureService,
) {
    @GetMapping("/tags/search")
    fun getSearchTags(): LegacySearchTagGroupsResponse = LegacySearchTagGroupsResponse(tagGroups = legacySearchTagService.searchTagGroups())

    @GetMapping("/lectures")
    fun searchLectures(
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) tags: List<Long>?,
    ): LegacyCourseSearchResponse {
        val criteria = legacySearchTagService.toCriteria(query, tags.orEmpty())
        val content = courseSearchService.searchPage(criteria, page, LEGACY_COURSE_PAGE_SIZE)
        val totalCount = courseSearchService.count(criteria)
        return LegacyCourseSearchResponse(
            content = content.map { it.toLegacyCourse() },
            page = page,
            size = LEGACY_COURSE_PAGE_SIZE,
            last = (page.toLong() + 1) * LEGACY_COURSE_PAGE_SIZE >= totalCount,
            totalCount = totalCount,
        )
    }

    @GetMapping("/lectures/{courseId}/semester-lectures")
    fun getSemesterLectures(
        @V1CurrentUser user: User,
        @PathVariable courseId: Long,
    ): LegacyCourseWithSemestersResponse {
        val result = courseSearchService.getCourseWithSemesters(courseId, user.id!!)
        val course = result.course
        val lecturesById = lectureService.getAllByIds(result.semesters.map { it.lectureId })
        return LegacyCourseWithSemestersResponse(
            id = course.id,
            title = course.title,
            instructor = course.instructor,
            department = course.department,
            courseNumber = course.courseNumber,
            credit = course.credit,
            academicYear = course.academicYear,
            category = course.category,
            classification = course.classification,
            semesterLectures =
                result.semesters.map {
                    val lecture = checkNotNull(lecturesById[it.lectureId])
                    LegacySemesterLectureDto(
                        id = it.lectureId,
                        year = it.year,
                        semester = it.semester.value,
                        credit = lecture.credit,
                        extraInfo = lecture.remark.orEmpty(),
                        academicYear = lecture.academicYear.orEmpty(),
                        category = lecture.category.orEmpty(),
                        classification = lecture.classification.orEmpty(),
                        myEvaluationExists = it.myEvaluationExists,
                    )
                },
        )
    }
}

private fun Course.toLegacyCourse(): LegacyCourseDto =
    LegacyCourseDto(
        id = id,
        title = title,
        instructor = instructor,
        department = department,
        courseNumber = courseNumber,
        credit = credit,
        academicYear = academicYear,
        category = category,
        classification = classification,
        evaluation = LegacyCourseEvaluationSummaryDto(avgRating = avgRating, evaluationCount = evalCount),
    )
