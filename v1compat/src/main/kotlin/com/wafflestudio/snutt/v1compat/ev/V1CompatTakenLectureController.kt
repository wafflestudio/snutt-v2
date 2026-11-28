package com.wafflestudio.snutt.v1compat.ev

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.CurrentClient
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.model.V1IdRepository
import com.wafflestudio.snutt.core.common.model.V1IdTable
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseSearchService
import com.wafflestudio.snutt.core.domain.evaluation.service.LectureTakenByUser
import com.wafflestudio.snutt.core.domain.evaluation.service.TakenLectureService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1EmailVerifiedRequired
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.data.repository.findByIdOrNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
    @param:JsonProperty("avg_rating")
    val avgRating: Double?,
    @param:JsonProperty("evaluation_count")
    val evaluationCount: Long,
    @param:JsonProperty("my_evaluation_exists")
    @param:Schema(
        description =
            "해당 강의에 해당 학기(`taken_year`, `taken_semester`)에 본인이 강의평을 썼으면 true, " +
                "강의평이 없거나 다른 학기에만 썼으면 false다.",
    )
    val myEvaluationExists: Boolean,
    @param:JsonProperty("recent_evaluations")
    @param:Schema(description = "해당 강의에 달린 강의평 중 최근 학기 순으로 최대 2개다. 수강 학기와 작성자를 구분하지 않는다.")
    val recentEvaluations: List<LegacyEvaluationWithSemesterDto>,
)

@RestController
@RequestMapping(V1_EV_SERVICE_PATH, V1_EV_PATH, EV_SERVICE_PATH, EV_PATH)
class V1CompatTakenLectureController(
    private val v1Ids: V1IdRepository,
    private val takenLectureService: TakenLectureService,
    private val legacyCourseRepository: LegacyCourseRepository,
) {
    @GetMapping("/users/me/lectures/latest")
    fun getMyLatestLectures(
        @V1CurrentUser user: User,
        @RequestParam(required = false) filter: String?,
        @CurrentClient clientInfo: ClientInfo,
    ): LegacyTakenLecturesResponse {
        val taken = takenLectureService.getMyLatestLectures(user.id!!, excludeEvaluated = filter == "no-my-evaluations")
        val metadata = legacyCourseRepository.getByIds(taken.map { it.course.id!! })
        val userIds = v1Ids.v1Ids(V1IdTable.USER, taken.flatMap { it.recentEvaluations }.mapNotNull { it.evaluation.userId })
        return LegacyTakenLecturesResponse(
            taken.map { it.toLegacyTakenLecture(metadata.getValue(it.course.id!!).localized(clientInfo.language), userIds) },
        )
    }

    private fun LectureTakenByUser.toLegacyTakenLecture(
        metadata: LegacyCourseMetadata,
        userIds: Map<Long, String>,
    ) = LegacyTakenLectureDto(
        id = course.id,
        title = metadata.title,
        instructor = metadata.instructor,
        department = metadata.department,
        courseNumber = course.courseNumber,
        credit = metadata.credit,
        academicYear = metadata.academicYear,
        category = metadata.category,
        classification = metadata.classification,
        takenYear = takenYear,
        takenSemester = takenSemester.value,
        avgRating = course.avgRating,
        evaluationCount = course.evalCount,
        myEvaluationExists = myEvaluationExists,
        recentEvaluations = recentEvaluations.map { it.toLegacyWithSemester(it.evaluation.userId?.let(userIds::getValue)) },
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
    private val v1Ids: V1IdRepository,
    private val courseSearchService: CourseSearchService,
    private val legacySearchTagService: LegacySearchTagService,
    private val legacyCourseRepository: LegacyCourseRepository,
    private val courseRepository: CourseRepository,
    private val lectureRepository: LectureRepository,
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
                .let { v1Ids.id(V1IdTable.LECTURE, it, ErrorType.COURSE_NOT_FOUND) }
                .let { lectureRepository.findByIdOrNull(it) }
                ?.courseId
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        return LegacyLectureIdResponse(id = courseId, snuttId = semesterLectureSnuttId)
    }

    @GetMapping("/lectures")
    fun searchEvLectures(
        @RequestParam(required = false, defaultValue = "") query: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) tags: List<Long>?,
        @CurrentClient clientInfo: ClientInfo,
    ): LegacyCourseSearchResponse {
        val criteria = legacySearchTagService.toCriteria(query, tags.orEmpty(), clientInfo.language)
        val content = legacyCourseRepository.search(criteria, page, LEGACY_COURSE_PAGE_SIZE)
        val totalCount = legacyCourseRepository.count(criteria)
        return LegacyCourseSearchResponse(
            content = content.map { it.localized(clientInfo.language).toLegacyCourse() },
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
        @CurrentClient clientInfo: ClientInfo,
    ): LegacyCourseWithSemestersResponse {
        val language = clientInfo.language
        val result = courseSearchService.getCourseWithLectures(courseId, user.id!!)
        val course = result.course
        val latestLecture = course.latestLectureId?.let { id -> result.lectures.firstOrNull { it.lecture.id == id }?.lecture }
        val next = SemesterCalendar.next()
        return LegacyCourseWithSemestersResponse(
            id = course.id,
            title = language.select(course.title, latestLecture?.courseTitleEn),
            instructor = language.select(course.instructor, latestLecture?.instructorEn),
            department = language.select(latestLecture?.department, latestLecture?.departmentEn),
            courseNumber = course.courseNumber,
            credit = latestLecture?.credit,
            academicYear = language.select(latestLecture?.academicYear, latestLecture?.academicYearEn),
            category = language.select(latestLecture?.category, latestLecture?.categoryEn),
            classification = language.select(latestLecture?.classification, latestLecture?.classificationEn),
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
                            extraInfo = language.select(lecture.remark, lecture.remarkEn).orEmpty(),
                            academicYear = language.select(lecture.academicYear, lecture.academicYearEn).orEmpty(),
                            category = language.select(lecture.category, lecture.categoryEn).orEmpty(),
                            classification = language.select(lecture.classification, lecture.classificationEn).orEmpty(),
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
