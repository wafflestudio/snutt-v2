package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// course.eval_count/avg_rating 비정규화 갱신 (PLAN.md §2: 강의평 트랜잭션 안에서 수행)
@Service
class CourseAggregateUpdater(
    private val courseRepository: CourseRepository,
    private val evaluationRepository: EvaluationRepository,
) {
    @Transactional
    fun update(courseId: Long) {
        val course = courseRepository.findById(courseId).orElse(null) ?: return
        val (count, avgRating) = evaluationRepository.findCourseAggregate(courseId)
        course.evalCount = count
        course.avgRating = avgRating
    }
}
