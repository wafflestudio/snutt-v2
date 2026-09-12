package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorCodec
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.common.pagination.toCursorPage
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCursor
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseSearchRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseSemesterRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CourseSemesterDisplay(
    val id: Long,
    val year: Int,
    val semester: Semester,
    val lectureId: Long?,
    val credit: Int,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val extraInfo: String?,
    val myEvaluationExists: Boolean,
)

data class CourseWithSemesters(
    val course: Course,
    val semesters: List<CourseSemesterDisplay>,
)

@Service
class CourseSearchService(
    private val courseSearchRepository: CourseSearchRepository,
    private val courseRepository: CourseRepository,
    private val courseSemesterRepository: CourseSemesterRepository,
    private val lectureRepository: LectureRepository,
    private val evaluationRepository: EvaluationRepository,
) {
    companion object {
        private const val PAGE_SIZE = 20
    }

    @Transactional(readOnly = true)
    fun count(criteria: CourseSearchCriteria): Long = courseSearchRepository.count(criteria)

    @Transactional(readOnly = true)
    fun searchPage(
        criteria: CourseSearchCriteria,
        page: Int,
        size: Int,
    ): List<Course> {
        if (size <= 0 || page < 0 || page > Int.MAX_VALUE / size) throw SnuttException(ErrorType.INVALID_PARAMETER)
        return courseSearchRepository.search(criteria, cursor = null, limit = size, offset = page * size)
    }

    @Transactional(readOnly = true)
    fun search(
        criteria: CourseSearchCriteria,
        cursor: String?,
    ): CursorPage<Course> {
        val decoded =
            CursorCodec.decode<CourseSearchCursor>(cursor)?.also {
                if (it.evalCount < 0 || it.courseId <= 0) {
                    throw SnuttException(ErrorType.INVALID_CURSOR)
                }
            }
        val results = courseSearchRepository.search(criteria, decoded, PAGE_SIZE + 1)
        return results.toCursorPage(
            PAGE_SIZE,
            cursorOf = { CourseSearchCursor(it.evalCount, it.id!!) },
            transform = { it },
        )
    }

    @Transactional(readOnly = true)
    fun getCourseWithSemesters(
        courseId: Long,
        userId: Long,
    ): CourseWithSemesters {
        val course = courseRepository.findById(courseId).orElseThrow { SnuttException(ErrorType.COURSE_NOT_FOUND) }
        val semesters = courseSemesterRepository.findByCourseIdOrderByYearDescSemesterDesc(courseId)
        val lecturesBySemester =
            lectureRepository
                .findByCourseIdOrderByYearDescSemesterDesc(courseId)
                .groupBy { it.year to it.semester }
                .mapValues { (_, offerings) -> offerings.minOf { it.id!! } }
        val evaluated =
            evaluationRepository
                .findEvaluatedCourseSemesters(userId, listOf(courseId))
                .map { it.year to it.semester }
                .toSet()
        return CourseWithSemesters(
            course = course,
            semesters =
                semesters.map {
                    CourseSemesterDisplay(
                        id = it.id!!,
                        year = it.year,
                        semester = it.semester,
                        lectureId = lecturesBySemester[it.year to it.semester],
                        credit = it.credit,
                        academicYear = it.academicYear,
                        category = it.category,
                        classification = it.classification,
                        extraInfo = it.extraInfo,
                        myEvaluationExists = (it.year to it.semester) in evaluated,
                    )
                },
        )
    }
}
