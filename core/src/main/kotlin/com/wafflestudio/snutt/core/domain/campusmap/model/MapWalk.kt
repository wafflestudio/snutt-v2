package com.wafflestudio.snutt.core.domain.campusmap.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

enum class MapSource {
    INITIAL,
    ADMIN,
}

enum class ElevationSource {
    DEM,
    INTERPOLATED,
    MANUAL,
}

enum class WalkEdgeKind {
    PATH,
    STEPS,
    ROAD,
    CONNECTOR,
    INDOOR,
}

@Entity
class MapWalkNode(
    var latitude: Double,
    var longitude: Double,
    @Column(name = "elevation_m")
    var elevationM: Double,
    @Enumerated(EnumType.STRING)
    var elevationSource: ElevationSource,
    @Enumerated(EnumType.STRING)
    var source: MapSource,
) : BaseEntity()

@Entity
class MapWalkEdge(
    @Column(name = "node_a_id")
    var nodeAId: Long,
    @Column(name = "node_b_id")
    var nodeBId: Long,
    @Enumerated(EnumType.STRING)
    var kind: WalkEdgeKind,
    var structureId: Long? = null,
    var isCovered: Boolean = false,
    var isBridgeOrTunnel: Boolean = false,
    var isLit: Boolean? = null,
    var openingHours: String? = null,
    var isBlocked: Boolean = false,
    var isEstimated: Boolean = false,
    @Enumerated(EnumType.STRING)
    var source: MapSource,
) : BaseEntity()
