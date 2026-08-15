package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CourseAggregateUpdater(
    private val courseRepository: CourseRepository,
    private val evaluationRepository: EvaluationRepository,
) {
    @Transactional
    fun update(courseId: Long) {
        val course = courseRepository.findByIdForUpdate(courseId) ?: return
        val (count, avgRating) = evaluationRepository.findCourseAggregate(courseId)
        course.evalCount = count
        course.avgRating = avgRating
    }
}
