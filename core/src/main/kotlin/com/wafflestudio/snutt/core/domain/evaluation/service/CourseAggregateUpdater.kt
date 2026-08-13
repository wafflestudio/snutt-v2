package com.wafflestudio.snutt.core.domain.evaluation.service

import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// course.eval_count/avg_rating 비정규화 갱신. 강의평 쓰기 트랜잭션 안에서 수행한다
@Service
class CourseAggregateUpdater(
    private val courseRepository: CourseRepository,
    private val evaluationRepository: EvaluationRepository,
) {
    /**
     * course 행 잠금이 같은 course에 대한 집계 갱신을 직렬화한다.
     * 커밋 시점의 eval_count/avg_rating은 is_hidden=false 강의평의 개수·평균과 같다.
     */
    @Transactional
    fun update(courseId: Long) {
        val course = courseRepository.findByIdForUpdate(courseId) ?: return
        val (count, avgRating) = evaluationRepository.findCourseAggregate(courseId)
        course.evalCount = count
        course.avgRating = avgRating
    }
}
