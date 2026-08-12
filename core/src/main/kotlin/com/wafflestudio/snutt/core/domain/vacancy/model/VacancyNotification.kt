package com.wafflestudio.snutt.core.domain.vacancy.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "vacancy_notification")
class VacancyNotification(
    var userId: Long,
    var lectureId: Long,
) : ExternalIdEntity()
