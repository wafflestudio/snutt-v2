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

data class LectureTakenByUser(
    val course: Course,
    val lectureExternalId: String,
    val takenYear: Int,
    val takenSemester: Semester,
)

// 최근 두 학기에 수강한 강의 목록. 강의평 작성 대상을 고르는 데 쓴다 (구 ev /v1/users/me/lectures/latest)
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
        // 현재 학기를 뺀 직전 두 학기 (v1 getLastTwoCourseBooksBeforeCurrent)
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
                    LectureTakenByUser(course, lecture.externalId, coursebook.year, coursebook.semester)
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
}
