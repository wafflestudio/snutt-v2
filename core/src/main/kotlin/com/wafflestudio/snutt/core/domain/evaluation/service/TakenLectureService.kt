package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class TakenCourseInput(
    val year: Int,
    val semester: Semester,
    val courseNumber: String?,
    val instructor: String?,
)

data class CourseTakenByUser(
    val course: Course,
    val takenYear: Int,
    val takenSemester: Semester,
)

data class LectureTakenByUser(
    val course: Course,
    val lectureId: Long,
    val takenYear: Int,
    val takenSemester: Semester,
)

@Service
class TakenLectureService(
    private val coursebookRepository: CoursebookRepository,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: LectureRepository,
    private val courseRepository: CourseRepository,
    private val evaluationRepository: EvaluationRepository,
) {
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

        val timetableSemester = timetables.associate { (timetable, coursebook) -> timetable.id to coursebook }
        val timetableLectures = timetableLectureRepository.findByTimetableIdIn(timetableSemester.keys.filterNotNull())
        val lecturesById =
            lectureRepository
                .findAllById(timetableLectures.mapNotNull { it.lectureId }.distinct())
                .associateBy { it.id }
        val coursesById =
            courseRepository
                .findAllById(lecturesById.values.mapNotNull { it.courseId }.distinct())
                .associateBy { it.id }

        val taken =
            timetableLectures
                .mapNotNull { timetableLecture ->
                    val coursebook = timetableSemester[timetableLecture.timetableId] ?: return@mapNotNull null
                    val lecture = timetableLecture.lectureId?.let { lecturesById[it] } ?: return@mapNotNull null
                    val course = lecture.courseId?.let { coursesById[it] } ?: return@mapNotNull null
                    LectureTakenByUser(course, lecture.id!!, coursebook.year, coursebook.semester)
                }.distinctBy { Triple(it.course.id, it.takenYear, it.takenSemester) }

        if (!excludeEvaluated) return taken
        return taken.filterNot { entry ->
            evaluationRepository.existsByCourseIdAndYearAndSemesterAndUserIdAndIsHiddenFalse(
                requireNotNull(entry.course.id),
                entry.takenYear,
                entry.takenSemester,
                userId,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getCoursesFromInputs(
        userId: Long,
        inputs: List<TakenCourseInput>,
        excludeEvaluated: Boolean,
    ): List<CourseTakenByUser> {
        val distinctInputs =
            inputs
                .filter { !it.courseNumber.isNullOrEmpty() && !it.instructor.isNullOrEmpty() }
                .associateBy { "${it.courseNumber}${it.instructor}" }
        return distinctInputs.values.mapNotNull { input ->
            val course =
                courseRepository.findByCourseNumberAndInstructor(
                    input.courseNumber!!,
                    input.instructor!!,
                ) ?: return@mapNotNull null
            if (
                excludeEvaluated &&
                evaluationRepository
                    .findByCourseIdAndUserIdAndIsHiddenFalseOrderByYearDescSemesterDescIdDesc(course.id!!, userId)
                    .isNotEmpty()
            ) {
                return@mapNotNull null
            }
            CourseTakenByUser(course, input.year, input.semester)
        }
    }
}
