package com.wafflestudio.snutt.core.domain.campusmap.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
class MapStructure(
    var name: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    var polygon: List<GeoCoordinate>,
    var floors: Int? = null,
    @Enumerated(EnumType.STRING)
    var source: MapSource,
) : BaseEntity()

@Entity
class MapBuilding(
    var buildingNumber: String,
    @Enumerated(EnumType.STRING)
    var campus: Campus,
    var nameKor: String,
    var nameEng: String = "",
    var latitude: Double,
    var longitude: Double,
    var structureId: Long? = null,
    @Enumerated(EnumType.STRING)
    var source: MapSource,
) : BaseEntity()

@Entity
class MapEntrance(
    var structureId: Long,
    var nodeId: Long,
    var floor: String? = null,
    var isWheelchairAccessible: Boolean? = null,
    var openingHours: String? = null,
    @Enumerated(EnumType.STRING)
    var source: MapSource,
) : BaseEntity()

@Entity
class MapAccessPoint(
    val buildingId: Long,
    val nodeId: Long,
) : BaseEntity()
