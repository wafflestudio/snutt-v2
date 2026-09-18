package com.wafflestudio.snutt.core.domain.building.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
class LectureBuilding(
    var buildingNumber: String,
    var buildingNameKor: String,
    var buildingNameEng: String = "",
    @Enumerated(EnumType.STRING)
    var campus: Campus,
    @JdbcTypeCode(SqlTypes.JSON)
    var locationInDms: GeoCoordinate? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var locationInDecimal: GeoCoordinate? = null,
) : BaseEntity()
