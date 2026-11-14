package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class LectureTakenByUser(
    val course: Course,
    val lecture: Lecture,
    val takenYear: Int,
    val takenSemester: Semester,
    val myEvaluationExists: Boolean,
    val recentEvaluations: List<EvaluationDisplay>,
) {
    val lectureId: Long get() = checkNotNull(lecture.id)
}

@Service
class TakenLectureService(
    private val coursebookRepository: CoursebookRepository,
    private val courseRepository: CourseRepository,
    private val evaluationRepository: EvaluationRepository,
    private val evaluationService: EvaluationService,
) {
    companion object {
        private const val RECENT_EVALUATION_COUNT = 2
    }

    @Transactional(readOnly = true)
    fun getMyLatestLectures(
        userId: Long,
        excludeEvaluated: Boolean,
    ): List<LectureTakenByUser> {
        val targetSemesters =
            coursebookRepository
                .findAllByOrderByYearDescSemesterDesc()
                .drop(1)
                .take(2)
                .map { YearAndSemester(it.year, it.semester) }
        if (targetSemesters.isEmpty()) return emptyList()

        val rows =
            courseRepository
                .findTakenLectures(userId, targetSemesters)
                .distinctBy { Triple(it.course.id, it.year, it.semester) }
        if (rows.isEmpty()) return emptyList()

        val courseIds = rows.map { it.course.id!! }.distinct()
        val evaluated =
            evaluationRepository
                .findEvaluatedCourseSemesters(userId, courseIds)
                .map { Triple(it.courseId, it.year, it.semester) }
                .toSet()
        val recentEvaluations =
            evaluationService.getRecentEvaluationsOfCourses(userId, courseIds, RECENT_EVALUATION_COUNT)
        val taken =
            rows.map { row ->
                val myEvaluationExists = Triple(row.course.id, row.year, row.semester) in evaluated
                LectureTakenByUser(
                    course = row.course,
                    lecture = row.lecture,
                    takenYear = row.year,
                    takenSemester = row.semester,
                    myEvaluationExists = myEvaluationExists,
                    recentEvaluations = recentEvaluations[row.course.id].orEmpty(),
                )
            }

        return if (excludeEvaluated) taken.filterNot { it.myEvaluationExists } else taken
    }
}
