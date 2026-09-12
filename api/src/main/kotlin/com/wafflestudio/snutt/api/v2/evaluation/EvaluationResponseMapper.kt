package com.wafflestudio.snutt.api.v2.evaluation

import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationDisplay
import org.springframework.stereotype.Component

@Component
class EvaluationResponseMapper(
    private val courseRepository: CourseRepository,
) {
    fun toResponse(page: CursorPage<EvaluationDisplay>): CursorPage<EvaluationResponse> {
        val courses = coursesOf(page.content)
        return page.map { toResponse(it, courses) }
    }

    fun toResponse(displays: List<EvaluationDisplay>): List<EvaluationResponse> {
        val courses = coursesOf(displays)
        return displays.map { toResponse(it, courses) }
    }

    fun toResponse(display: EvaluationDisplay): EvaluationResponse = toResponse(display, coursesOf(listOf(display)))

    private fun coursesOf(displays: List<EvaluationDisplay>): Map<Long, Course> =
        courseRepository.findAllById(displays.map { it.evaluation.courseId }.distinct()).associateBy { it.id!! }

    private fun toResponse(
        display: EvaluationDisplay,
        courses: Map<Long, Course>,
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
            isHidden = evaluation.isHidden,
            isReported = evaluation.isReported,
            isLiked = display.isLiked,
            fromSnuev = evaluation.fromSnuev,
            year = evaluation.year,
            semester = evaluation.semester,
            isModifiable = display.isModifiable,
            isReportable = display.isReportable,
        )
    }
}
