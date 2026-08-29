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
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CourseSemester(
    val year: Int,
    val semester: Semester,
    val lectureId: Long,
    val myEvaluationExists: Boolean,
)

data class CourseWithSemesters(
    val course: Course,
    val semesters: List<CourseSemester>,
)

@Service
class CourseSearchService(
    private val courseSearchRepository: CourseSearchRepository,
    private val courseRepository: CourseRepository,
    private val lectureRepository: LectureRepository,
    private val evaluationRepository: EvaluationRepository,
) {
    companion object {
        private const val PAGE_SIZE = 20
    }

    @Transactional(readOnly = true)
    fun count(criteria: CourseSearchCriteria): Long = courseSearchRepository.count(criteria)

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
            courseSearchRepository.count(criteria),
            { CourseSearchCursor(it.evalCount, it.id!!) },
            { it },
        )
    }

    @Transactional(readOnly = true)
    fun getCourseWithSemesters(
        courseId: Long,
        userId: Long,
    ): CourseWithSemesters {
        val course = courseRepository.findById(courseId).orElseThrow { SnuttException(ErrorType.COURSE_NOT_FOUND) }
        val lectures =
            lectureRepository
                .findByCourseIdOrderByYearDescSemesterDesc(courseId)
                .groupBy { it.year to it.semester }
                .values
                .map { offerings -> offerings.minBy { it.id!! } }
                .sortedWith(compareByDescending<Lecture> { it.year }.thenByDescending { it.semester.value })
        val evaluated =
            lectures
                .filter { lecture ->
                    evaluationRepository.existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(
                        courseId,
                        lecture.year,
                        lecture.semester,
                        userId,
                    )
                }.map { lecture -> lecture.year to lecture.semester }
                .toSet()
        return CourseWithSemesters(
            course = course,
            semesters =
                lectures.map {
                    CourseSemester(
                        year = it.year,
                        semester = it.semester,
                        lectureId = it.id!!,
                        myEvaluationExists = (it.year to it.semester) in evaluated,
                    )
                },
        )
    }
}
