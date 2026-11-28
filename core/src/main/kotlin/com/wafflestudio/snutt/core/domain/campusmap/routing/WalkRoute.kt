package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind

data class WalkStep(
    val edge: Int,
    val forward: Boolean,
)

data class WalkSegment(
    val kind: WalkEdgeKind,
    val estimated: Boolean,
    val points: List<GeoCoordinate>,
)

data class ElevationPoint(
    val distanceM: Double,
    val elevationM: Double,
    val kind: WalkEdgeKind?,
)

data class WalkRoute(
    val start: Int,
    val end: Int,
    val steps: List<WalkStep>,
    val distanceM: Double,
    val timeSec: Double,
    val ascentM: Double,
    val descentM: Double,
    val stairsM: Double,
    val stairFlights: Int,
    val indoorM: Double,
    val coveredM: Double,
    val estimatedM: Double,
    val points: List<GeoCoordinate>,
    val segments: List<WalkSegment>,
    val elevationProfile: List<ElevationPoint>,
)
