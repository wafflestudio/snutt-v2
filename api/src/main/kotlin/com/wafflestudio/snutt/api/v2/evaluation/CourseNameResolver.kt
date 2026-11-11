package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import org.springframework.stereotype.Component

data class CourseName(
    val title: String,
    val instructor: String,
)

@Component
class CourseNameResolver(
    private val lectureRepository: LectureRepository,
) {
    fun resolve(
        courses: Collection<Course>,
        language: Language,
    ): Map<Long, CourseName> {
        val latestLectures =
            if (language == Language.EN) {
                lectureRepository.findAllById(courses.mapNotNull { it.latestLectureId }.distinct()).associateBy { it.id!! }
            } else {
                emptyMap()
            }
        return courses.associate { course ->
            val lecture = course.latestLectureId?.let(latestLectures::get)
            course.id!! to
                CourseName(
                    title = language.select(course.title, lecture?.courseTitleEn),
                    instructor = language.select(course.instructor, lecture?.instructorEn),
                )
        }
    }
}
