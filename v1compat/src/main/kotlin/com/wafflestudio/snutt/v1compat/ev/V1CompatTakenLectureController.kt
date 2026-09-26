package com.wafflestudio.snutt.v1compat.ev

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.coursebook.service.SemesterService
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService
import com.wafflestudio.snutt.core.domain.evaluation.service.LectureTakenByUser
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1EmailVerifiedRequired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class LegacyTakenLecturesResponse(
    val content: List<LegacyTakenLectureDto>,
    @param:JsonProperty("total_count")
    val totalCount: Int = content.size,
)

data class LegacyTakenLectureDto(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    @param:JsonProperty("course_number")
    val courseNumber: String,
    val credit: Int?,
    @param:JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    @param:JsonProperty("taken_year")
    val takenYear: Int,
    @param:JsonProperty("taken_semester")
    val takenSemester: Int,
)

@RestController
@RequestMapping(V1_EV_SERVICE_PATH, V1_EV_PATH, EV_SERVICE_PATH, EV_PATH)
class V1CompatTakenLectureController(
    private val takenLectureService: TakenLectureService,
    private val legacyCourseRepository: LegacyCourseRepository,
) {
    @GetMapping("/users/me/lectures/latest")
    fun getMyLatestLectures(
        @V1CurrentUser user: User,
        @RequestParam(required = false) filter: String?,
    ): LegacyTakenLecturesResponse {
        val taken = takenLectureService.getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
        val metadata = legacyCourseRepository.getByIds(taken.map { it.course.id!! })
        return LegacyTakenLecturesResponse(taken.map { it.toLegacyTakenLecture(metadata.getValue(it.course.id!!)) })
    }

    private fun LectureTakenByUser.toLegacyTakenLecture(metadata: LegacyCourseMetadata) =
        LegacyTakenLectureDto(
            id = course.id,
            title = course.title,
            instructor = course.instructor,
            department = metadata.department,
            courseNumber = course.courseNumber,
            credit = metadata.credit,
            academicYear = metadata.academicYear,
            category = metadata.category,
            classification = metadata.classification,
            takenYear = takenYear,
            takenSemester = takenSemester.value,
        )
}

data class LegacySearchTagGroupsResponse(
    @param:JsonProperty("tag_groups")
    val tagGroups: List<LegacyEvTagGroupDto>,
)

data class LegacyCourseSearchResponse(
    val content: List<LegacyCourseDto>,
    val page: Int,
    val size: Int,
    val last: Boolean,
    @param:JsonProperty("total_count")
    val totalCount: Long,
)

data class LegacyCourseDto(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    @param:JsonProperty("course_number")
    val courseNumber: String,
    val credit: Int?,
    @param:JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evaluation: LegacyCourseEvaluationSummaryDto,
)

data class LegacyCourseEvaluationSummaryDto(
    @param:JsonProperty("avg_rating")
    val avgRating: Double?,
    @param:JsonProperty("evaluation_count")
    val evaluationCount: Long,
)

data class LegacyCourseWithSemestersResponse(
    val id: Long?,
    val title: String,
    val instructor: String,
    val department: String?,
    @param:JsonProperty("course_number")
    val courseNumber: String,
    val credit: Int?,
    @param:JsonProperty("academic_year")
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    @param:JsonProperty("semester_lectures")
    val semesterLectures: List<LegacySemesterLectureDto>,
)

data class LegacySemesterLectureDto(
    val id: Long,
    val year: Int,
    val semester: Int,
    val credit: Int,
    @param:JsonProperty("extra_info")
    val extraInfo: String,
    @param:JsonProperty("academic_year")
    val academicYear: String,
    val category: String,
    val classification: String,
    @param:JsonProperty("my_evaluation_exists")
    val myEvaluationExists: Boolean,
)

data class LegacyLectureIdResponse(
    val id: Long,
    val snuttId: String? = null,
    val evLectureId: Long = id,
)

private const val LEGACY_COURSE_PAGE_SIZE = 20

@RestController
@V1EmailVerifiedRequired
@RequestMapping(V1_EV_SERVICE_PATH, V1_EV_PATH, EV_SERVICE_PATH, EV_PATH)
class V1CompatCourseSearchController(
    private val courseSearchService: CourseSearchService,
    private val legacySearchTagService: LegacySearchTagService,
    private val legacyCourseRepository: LegacyCourseRepository,
    private val courseRepository: CourseRepository,
    private val lectureRepository: LectureRepository,
    private val semesterService: SemesterService,
) {
    @GetMapping("/tags/search")
    fun getSearchTags(): LegacySearchTagGroupsResponse = LegacySearchTagGroupsResponse(tagGroups = legacySearchTagService.searchTagGroups())

    @GetMapping("/lectures/id", params = ["course_number", "instructor"])
    fun getCourseIdByCourseNumber(
        @RequestParam("course_number") courseNumber: String,
        @RequestParam instructor: String,
    ): LegacyLectureIdResponse {
        val course =
            courseRepository.findByCourseNumberAndInstructor(courseNumber, instructor)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        return LegacyLectureIdResponse(id = course.id!!)
    }

    @GetMapping("/lectures/id", params = ["semesterLectureSnuttId"])
    fun getCourseIdByLectureId(
        @RequestParam semesterLectureSnuttId: String,
    ): LegacyLectureIdResponse {
        val courseId =
            semesterLectureSnuttId
                .toLongOrNull()
                ?.let { lectureRepository.findByIdOrNull(it) }
                ?.courseId
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        return LegacyLectureIdResponse(id = courseId, snuttId = semesterLectureSnuttId)
    }

    @GetMapping("/lectures")
    fun searchLectures(
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) tags: List<Long>?,
    ): LegacyCourseSearchResponse {
        val criteria = legacySearchTagService.toCriteria(query, tags.orEmpty())
        val content = legacyCourseRepository.search(criteria, page, LEGACY_COURSE_PAGE_SIZE)
        val totalCount = legacyCourseRepository.count(criteria)
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
        val result = courseSearchService.getCourseWithLectures(courseId, user.id!!)
        val course = legacyCourseRepository.get(courseId)
        val next = semesterService.getNextYearAndSemester(Instant.now())
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
                result.lectures
                    .filterNot { it.lecture.year == next.year && it.lecture.semester == next.semester }
                    .groupBy { it.lecture.year to it.lecture.semester }
                    .values
                    .map { group ->
                        val display = group.minBy { it.lecture.id!! }
                        val lecture = display.lecture
                        LegacySemesterLectureDto(
                            id = lecture.id!!,
                            year = lecture.year,
                            semester = lecture.semester.value,
                            credit = lecture.credit,
                            extraInfo = lecture.remark.orEmpty(),
                            academicYear = lecture.academicYear.orEmpty(),
                            category = lecture.category.orEmpty(),
                            classification = lecture.classification.orEmpty(),
                            myEvaluationExists = display.myEvaluationExists,
                        )
                    },
        )
    }
}

private fun LegacyCourseMetadata.toLegacyCourse(): LegacyCourseDto =
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
