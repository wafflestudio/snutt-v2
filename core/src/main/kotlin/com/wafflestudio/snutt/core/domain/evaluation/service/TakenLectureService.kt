package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
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
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: LectureRepository,
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
        val coursebooks =
            coursebookRepository
                .findAllByOrderByYearDescSemesterDesc()
                .drop(1)
                .take(2)
        if (coursebooks.isEmpty()) return emptyList()

        val timetables =
            coursebooks.flatMap { coursebook ->
                timetableRepository
                    .findByUserIdAndYearAndSemester(userId, coursebook.year, coursebook.semester)
                    .map { it to coursebook }
            }
        if (timetables.isEmpty()) return emptyList()

        val timetableSemester = timetables.associate { (timetable, coursebook) -> timetable.id!! to coursebook }
        val timetableLectures = timetableLectureRepository.findByTimetableIdIn(timetableSemester.keys)
        val lecturesById =
            lectureRepository
                .findAllById(timetableLectures.mapNotNull { it.lectureId }.distinct())
                .associateBy { it.id }
        val coursesById =
            courseRepository
                .findAllById(lecturesById.values.mapNotNull { it.courseId }.distinct())
                .associateBy { it.id }
        if (coursesById.isEmpty()) return emptyList()

        val evaluated =
            evaluationRepository
                .findEvaluatedCourseSemesters(userId, coursesById.keys.filterNotNull())
                .map { Triple(it.courseId, it.year, it.semester) }
                .toSet()
        val recentEvaluations =
            evaluationService.getRecentEvaluationsOfCourses(userId, coursesById.keys.filterNotNull(), RECENT_EVALUATION_COUNT)
        val taken =
            timetableLectures
                .mapNotNull { timetableLecture ->
                    val coursebook = timetableSemester[timetableLecture.timetableId] ?: return@mapNotNull null
                    val lecture = timetableLecture.lectureId?.let { lecturesById[it] } ?: return@mapNotNull null
                    val course = lecture.courseId?.let { coursesById[it] } ?: return@mapNotNull null
                    val myEvaluationExists = Triple(course.id, coursebook.year, coursebook.semester) in evaluated
                    LectureTakenByUser(
                        course,
                        lecture,
                        coursebook.year,
                        coursebook.semester,
                        myEvaluationExists,
                        recentEvaluations[course.id].orEmpty(),
                    )
                }.distinctBy { Triple(it.course.id, it.takenYear, it.takenSemester) }

        return if (excludeEvaluated) taken.filterNot { it.myEvaluationExists } else taken
    }
}
