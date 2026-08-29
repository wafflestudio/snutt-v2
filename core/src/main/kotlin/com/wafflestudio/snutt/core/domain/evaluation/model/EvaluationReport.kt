package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "evaluation_report")
class EvaluationReport(
    var evaluationId: Long,
    var userId: Long,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var content: String,
    var isHidden: Boolean = false,
) : BaseEntity()
