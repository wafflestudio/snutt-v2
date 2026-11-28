package com.wafflestudio.snutt.core.domain.campusmap.service

import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.campusmap.routing.TransitRoute
import com.wafflestudio.snutt.core.domain.campusmap.routing.TransitRouter
import com.wafflestudio.snutt.core.domain.campusmap.routing.WalkRoute
import com.wafflestudio.snutt.core.domain.campusmap.routing.WalkRouter
import org.springframework.stereotype.Service

data class CampusRoutes(
    val walk: WalkRoute?,
    val fastestTransit: TransitRoute?,
    val leastWalkingTransit: TransitRoute?,
)

@Service
class CampusRouteService(
    private val provider: CampusMapProvider,
) {
    fun routes(
        from: PlaceInfo,
        to: PlaceInfo,
        allowIndoor: Boolean = true,
    ): CampusRoutes? {
        val map = provider.current()
        val origin = map.building(from)?.accessNodes ?: return null
        val destination = map.building(to)?.accessNodes ?: return null
        val walk = WalkRouter.route(map.graph, origin, destination, allowIndoor)

        val candidates =
            WALK_WEIGHTS.mapNotNull { weight ->
                TransitRouter
                    .route(map.graph, map.transit, origin, destination, weight, allowIndoor)
                    ?.takeIf { walk == null || it.timeSec < walk.timeSec }
            }
        return CampusRoutes(
            walk = walk,
            fastestTransit = candidates.minByOrNull { it.timeSec },
            leastWalkingTransit = candidates.minWithOrNull(compareBy({ it.walkSec }, { it.timeSec })),
        )
    }

    companion object {
        private val WALK_WEIGHTS = listOf(1.0, 1.5, 2.0, 3.0, 5.0, 10.0, 1e6)
    }
}
