package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity

@Entity
class DiaryDailyClassType(
    var name: String,
    var active: Boolean = true,
) : BaseEntity()
