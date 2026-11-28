package com.wafflestudio.snutt.core.domain.diary.model

import com.wafflestudio.snutt.core.common.model.V1Entity
import jakarta.persistence.Entity

@Entity
class DiaryDailyClassType(
    var name: String,
    var active: Boolean = true,
) : V1Entity()
