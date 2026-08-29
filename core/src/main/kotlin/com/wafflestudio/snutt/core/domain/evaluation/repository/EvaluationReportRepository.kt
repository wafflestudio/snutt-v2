package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationReport
import org.springframework.data.jpa.repository.JpaRepository

interface EvaluationReportRepository : JpaRepository<EvaluationReport, Long> {
    fun existsByEvaluationIdAndUserId(
        evaluationId: Long,
        userId: Long,
    ): Boolean
}
