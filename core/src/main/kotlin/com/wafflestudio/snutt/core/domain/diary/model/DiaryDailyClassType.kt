package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "diary_daily_class_type")
class DiaryDailyClassType(
    var name: String,
    var active: Boolean = true,
) : BaseEntity()
