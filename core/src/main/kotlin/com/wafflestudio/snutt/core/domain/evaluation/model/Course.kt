package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity

@Entity
class Course(
    var courseNumber: String,
    var instructor: String,
    var title: String,
    var evalCount: Long = 0,
    var avgRating: Double? = null,
    var avgGradeSatisfaction: Double? = null,
    var avgTeachingSkill: Double? = null,
    var avgGains: Double? = null,
    var avgLifeBalance: Double? = null,
) : BaseEntity()
