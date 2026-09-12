package com.wafflestudio.snutt.core.domain.evaluation.service

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

data class CourseLectureDisplay(
    val lecture: Lecture,
    val myEvaluationExists: Boolean,
)

data class CourseWithLectures(
    val course: Course,
    val lectures: List<CourseLectureDisplay>,
)

@Service
class CourseSearchService(
    private val courseSearchRepository: CourseSearchRepository,
    private val courseRepository: CourseRepository,
    private val lectureRepository: LectureRepository,
    private val evaluationRepository: EvaluationRepository,
) {
    @Transactional(readOnly = true)
    fun search(
        criteria: CourseSearchCriteria,
        cursor: String?,
    ): CursorPage<Course> {
        val decoded =
            CursorCodec.decode<CourseSearchCursor>(cursor)?.also {
                if (it.evalCount < 0 || it.courseId <= 0) throw SnuttException(ErrorType.INVALID_CURSOR)
            }
        return courseSearchRepository.search(criteria, decoded, PAGE_SIZE + 1).toCursorPage(
            PAGE_SIZE,
            cursorOf = { CourseSearchCursor(it.evalCount, it.id!!) },
            transform = { it },
        )
    }

    @Transactional(readOnly = true)
    fun getCourseWithLectures(
        courseId: Long,
        userId: Long,
    ): CourseWithLectures {
        val course = courseRepository.findById(courseId).orElseThrow { SnuttException(ErrorType.COURSE_NOT_FOUND) }
        val evaluated = evaluationRepository.findEvaluatedCourseSemesters(userId, listOf(courseId)).map { it.year to it.semester }.toSet()
        return CourseWithLectures(
            course,
            lectureRepository.findByCourseIdOrderByYearDescSemesterDesc(courseId).map {
                CourseLectureDisplay(it, (it.year to it.semester) in evaluated)
            },
        )
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
