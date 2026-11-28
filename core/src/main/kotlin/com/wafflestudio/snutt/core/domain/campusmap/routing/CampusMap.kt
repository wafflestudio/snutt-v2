package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo

data class CampusMapBuilding(
    val buildingId: Long,
    val campus: Campus,
    val buildingNumber: String,
    val nameKor: String,
    val nameEng: String,
    val location: GeoCoordinate,
    val accessNodes: List<Int>,
    val accessEstimated: Boolean,
)

class CampusMap(
    val version: Long,
    val graph: WalkGraph,
    val buildings: Map<PlaceInfo, CampusMapBuilding>,
    val transit: TransitNetwork,
) {
    fun building(place: PlaceInfo): CampusMapBuilding? = buildings[place]
}
