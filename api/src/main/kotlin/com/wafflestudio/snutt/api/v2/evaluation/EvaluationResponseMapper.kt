package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay
import org.springframework.stereotype.Component

@Component
class EvaluationResponseMapper(
    private val courseRepository: CourseRepository,
    private val courseNameResolver: CourseNameResolver,
) {
    fun toResponse(
        page: CursorPage<EvaluationDisplay>,
        language: Language,
    ): CursorPage<EvaluationResponse> {
        val courses = courseNamesOf(page.content, language)
        return page.map { toResponse(it, courses) }
    }

    fun toResponse(
        displays: List<EvaluationDisplay>,
        language: Language,
    ): List<EvaluationResponse> {
        val courses = courseNamesOf(displays, language)
        return displays.map { toResponse(it, courses) }
    }

    fun toResponse(
        display: EvaluationDisplay,
        language: Language,
    ): EvaluationResponse = toResponse(display, courseNamesOf(listOf(display), language))

    private fun courseNamesOf(
        displays: List<EvaluationDisplay>,
        language: Language,
    ): Map<Long, CourseName> =
        courseNameResolver.resolve(courseRepository.findAllById(displays.map { it.evaluation.courseId }.distinct()), language)

    private fun toResponse(
        display: EvaluationDisplay,
        courses: Map<Long, CourseName>,
    ): EvaluationResponse {
        val evaluation = display.evaluation
        val course = courses.getValue(evaluation.courseId)
        return EvaluationResponse(
            id = checkNotNull(evaluation.id),
            courseId = evaluation.courseId,
            courseTitle = course.title,
            instructor = course.instructor,
            content = evaluation.content,
            gradeSatisfaction = evaluation.gradeSatisfaction,
            teachingSkill = evaluation.teachingSkill,
            gains = evaluation.gains,
            lifeBalance = evaluation.lifeBalance,
            rating = evaluation.rating,
            likeCount = evaluation.likeCount,
            isLiked = display.isLiked,
            fromSnuev = evaluation.fromSnuev,
            year = evaluation.year,
            semester = evaluation.semester,
            isModifiable = display.isModifiable,
        )
    }
}
