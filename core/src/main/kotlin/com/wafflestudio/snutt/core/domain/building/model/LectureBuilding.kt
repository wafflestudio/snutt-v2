package com.wafflestudio.snutt.core.domain.building.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "lecture_building")
class LectureBuilding(
    var buildingNumber: String,
    var buildingNameKor: String,
    var buildingNameEng: String = "",
    @Enumerated(EnumType.STRING)
    var campus: Campus,
    @JdbcTypeCode(SqlTypes.JSON)
    var locationInDms: GeoCoordinate? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location_in_decimal")
    var locationInDecimal: GeoCoordinate? = null,
) : BaseEntity()
