package com.wafflestudio.snutt.core.domain.campusmap.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity

@Entity
class MapState(
    var version: Long,
) : BaseEntity()
