package com.wafflestudio.snutt.core.domain.trace.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity

@Entity
class ApiTraceTarget(
    var userId: Long,
    var memo: String? = null,
) : BaseEntity()
