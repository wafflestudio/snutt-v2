package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseSearchRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
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
    @Transactional(readOnly = true)
    fun search(criteria: CourseSearchCriteria): List<Course> = courseSearchRepository.search(criteria)

    @Transactional(readOnly = true)
    fun count(criteria: CourseSearchCriteria): Long = courseSearchRepository.count(criteria)

    @Transactional(readOnly = true)
    fun getCourseWithSemesters(
        courseId: Long,
        userId: Long,
    ): CourseWithSemesters {
        val course = courseRepository.findById(courseId).orElseThrow { SnuttException(ErrorType.COURSE_NOT_FOUND) }
        val lectures = lectureRepository.findByCourseIdOrderByYearDescSemesterDesc(courseId)
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
