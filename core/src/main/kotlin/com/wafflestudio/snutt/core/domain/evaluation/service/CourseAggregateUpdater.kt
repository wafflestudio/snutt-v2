package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
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
        val course = courseRepository.findForUpdateById(courseId) ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)
        val aggregate = evaluationRepository.findCourseAggregate(courseId)
        course.evalCount = aggregate.evalCount
        course.avgRating = aggregate.avgRating
        course.avgGradeSatisfaction = aggregate.avgGradeSatisfaction
        course.avgTeachingSkill = aggregate.avgTeachingSkill
        course.avgGains = aggregate.avgGains
        course.avgLifeBalance = aggregate.avgLifeBalance
    }
}
