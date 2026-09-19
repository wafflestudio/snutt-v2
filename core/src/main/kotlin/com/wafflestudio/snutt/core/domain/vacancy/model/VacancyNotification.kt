package com.wafflestudio.snutt.core.domain.vacancy.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity

@Entity
class VacancyNotification(
    var userId: Long,
    var lectureId: Long,
) : BaseEntity()
