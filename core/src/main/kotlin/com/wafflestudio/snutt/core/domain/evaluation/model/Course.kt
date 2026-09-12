package com.wafflestudio.snutt.core.domain.evaluation.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "course")
class Course(
    var courseNumber: String,
    var instructor: String,
    var title: String,
    var evalCount: Long = 0,
    var avgRating: Double? = null,
) : BaseEntity()
