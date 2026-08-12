package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "evaluation_like")
class EvaluationLike(
    var evaluationId: Long,
    var userId: Long,
) : BaseEntity()
