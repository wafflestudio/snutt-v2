package com.wafflestudio.snutt.core.domain.campusmap.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class TransitLineKind(
    val irregularity: Double,
) {
    CITY_BUS(0.3),
    SHUTTLE(0.2),
}

@Entity
class MapTransitStop(
    var name: String,
    var arsId: String? = null,
    var latitude: Double,
    var longitude: Double,
    var nodeId: Long,
    @Enumerated(EnumType.STRING)
    var source: MapSource,
) : BaseEntity()

@Entity
class MapTransitLine(
    var name: String,
    @Enumerated(EnumType.STRING)
    var kind: TransitLineKind,
    var minHeadwayMin: Int,
    var maxHeadwayMin: Int,
    var headwayNote: String = "",
    var isHeadwayEstimated: Boolean,
    @Enumerated(EnumType.STRING)
    var source: MapSource,
) : BaseEntity()

@Entity
class MapTransitLineStop(
    val lineId: Long,
    var runNo: Int,
    var seq: Int,
    var stopId: Long,
    var displayName: String? = null,
    var rideSecToNext: Int? = null,
    var isRideSecEstimated: Boolean = true,
    @Column(name = "road_m_to_next")
    var roadMToNext: Int? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var pathToNext: List<GeoCoordinate>? = null,
) : BaseEntity()
