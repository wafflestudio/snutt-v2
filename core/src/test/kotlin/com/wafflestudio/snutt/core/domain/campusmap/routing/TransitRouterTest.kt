package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class TransitRouterTest {
    private val graph =
        TestGraph()
            .apply {
                node(1, -50.0)
                node(10, 0.0)
                node(11, 1000.0)
                node(12, 2000.0)
                node(13, 3000.0)
                node(2, 3050.0)
                edge(1, 10)
                edge(10, 11, WalkEdgeKind.ROAD)
                edge(11, 12, WalkEdgeKind.ROAD)
                edge(12, 13, WalkEdgeKind.ROAD)
                edge(13, 2)
            }.build()

    private fun stop(
        id: Long,
        node: Long,
        ride: Int?,
    ) = stop(graph, id, node, ride)

    private fun stop(
        graph: WalkGraph,
        id: Long,
        node: Long,
        ride: Int?,
    ) = TransitPatternStop(
        stopId = id,
        stopName = "정류장$id",
        displayName = "정류장$id",
        node = graph.at(node),
        rideSecToNext = ride,
        roadMToNext = ride?.let { 1000 },
        pathToNext = if (ride == null) emptyList() else listOf(graph.coordinate(graph.at(node)), GeoCoordinate(0.0, 0.0)),
    )

    private fun pattern(
        id: Long,
        name: String,
        headwayMin: Int,
        stops: List<TransitPatternStop>,
    ) = TransitPattern(id, name, Headway.fixed(headwayMin), 0.0, stops)

    private fun route(
        graph: WalkGraph,
        network: TransitNetwork,
        from: Long,
        to: Long,
        walkWeight: Double = 1.0,
        allowIndoor: Boolean = true,
    ) = TransitRouter.route(graph, network, listOf(graph.at(from)), listOf(graph.at(to)), walkWeight, allowIndoor)

    private val line = pattern(7, "5511", 10, listOf(stop(100, 10, 120), stop(101, 11, 150), stop(103, 13, null)))

    @Test
    fun ridesWhenFasterAndSplitsLegs() {
        val result = route(graph, TransitNetwork(listOf(line)), 1, 2)!!

        assertThat(result.legs.map { it::class.simpleName }).containsExactly("Walk", "Ride", "Walk")
        val ride = result.legs[1] as TransitLeg.Ride
        assertThat(ride.from.stopId).isEqualTo(100L)
        assertThat(ride.to.stopId).isEqualTo(103L)
        assertThat(ride.stopCount).isEqualTo(2)
        assertThat(result.waitSec).isEqualTo(300.0)
        assertThat(result.rideSec).isEqualTo(270.0)
        assertThat(ride.distanceM).isEqualTo(2000.0)
        assertThat(ride.path).hasSize(3)
        assertThat(ride.stopPoints).hasSize(3)
        assertThat(result.walkDistanceM).isCloseTo(100.0, within(0.01))
        assertThat(result.timeSec).isCloseTo(result.walkSec + result.waitSec + result.rideSec, within(1e-6))
        assertThat(result.timeSec).isLessThan(WalkRouter.route(graph, listOf(graph.at(1)), listOf(graph.at(2)))!!.timeSec)
    }

    @Test
    fun alwaysRidesAtLeastOnceEvenIfWalkingIsFaster() {
        val result = route(graph, TransitNetwork(listOf(line)), 10, 11)!!

        val rides = result.legs.filterIsInstance<TransitLeg.Ride>()
        assertThat(rides).hasSize(1)
        assertThat(result.timeSec).isEqualTo(300.0 + 120.0)
        assertThat(result.legs.filterIsInstance<TransitLeg.Walk>()).isEmpty()
    }

    @Test
    fun heavierWalkWeightTradesTimeForLessWalking() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0)
                    node(10, 100.0)
                    node(11, 1000.0)
                    node(12, 0.0, eastM = 10.0)
                    node(2, 1000.0, eastM = 10.0)
                    edge(1, 10)
                    edge(10, 11, WalkEdgeKind.ROAD)
                    edge(1, 12)
                    edge(12, 2, WalkEdgeKind.ROAD)
                    edge(11, 2)
                }.build()
        val fast =
            pattern(1, "fast", 2, listOf(stop(graph, 1, 10, 60), stop(graph, 2, 11, null)))
        val nearby =
            pattern(2, "nearby", 20, listOf(stop(graph, 3, 12, 60), stop(graph, 4, 2, null)))
        val network = TransitNetwork(listOf(fast, nearby))
        val fastest = route(graph, network, 1, 2)!!
        val leastWalking = route(graph, network, 1, 2, walkWeight = 10.0)!!

        assertThat(
            fastest.legs
                .filterIsInstance<TransitLeg.Ride>()
                .single()
                .lines
                .single()
                .lineName,
        ).isEqualTo("fast")
        assertThat(
            leastWalking.legs
                .filterIsInstance<TransitLeg.Ride>()
                .single()
                .lines
                .single()
                .lineName,
        ).isEqualTo("nearby")
        assertThat(leastWalking.walkSec).isLessThan(fastest.walkSec)
        assertThat(leastWalking.timeSec).isGreaterThan(fastest.timeSec)
    }

    @Test
    fun walkingLegsSkipIndoorPassagesWhenExcluded() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0)
                    node(3, 0.0, eastM = 60.0)
                    node(10, 50.0)
                    node(11, 2000.0)
                    node(2, 2050.0)
                    edge(1, 10, WalkEdgeKind.INDOOR)
                    edge(1, 3)
                    edge(3, 10)
                    edge(10, 11, WalkEdgeKind.ROAD)
                    edge(11, 2)
                }.build()
        val network = TransitNetwork(listOf(pattern(1, "bus", 2, listOf(stop(graph, 1, 10, 60), stop(graph, 2, 11, null)))))

        val result = route(graph, network, 1, 2, allowIndoor = false)!!

        val firstWalk = result.legs.first() as TransitLeg.Walk
        assertThat(firstWalk.route.indoorM).isZero()
        assertThat(firstWalk.route.steps).hasSize(2)
    }

    @Test
    fun combinesLinesServingSameStopsAndWaitsForWhicheverComesFirst() {
        val other = pattern(8, "셔틀", 10, listOf(stop(200, 10, 150), stop(201, 13, null)))

        val result = route(graph, TransitNetwork(listOf(line, other)), 1, 2)!!

        val ride = result.legs.filterIsInstance<TransitLeg.Ride>().single()
        assertThat(ride.lines.map { it.lineName }).containsExactly("셔틀", "5511")
        assertThat(ride.waitSec).isEqualTo(150.0)
        assertThat(ride.rideSec).isEqualTo(270.0)
        assertThat(ride.from.stopId).isEqualTo(200L)
        assertThat(ride.waitSec + ride.rideSec).isLessThan(300.0 + 150.0)
    }

    @Test
    fun leavesOutLineThatOnlyMakesTheGroupSlower() {
        val detour = pattern(9, "우회", 10, listOf(stop(300, 10, 2000), stop(301, 13, null)))

        val result = route(graph, TransitNetwork(listOf(line, detour)), 1, 2)!!

        val ride = result.legs.filterIsInstance<TransitLeg.Ride>().single()
        assertThat(ride.lines.map { it.lineName }).containsExactly("5511")
        assertThat(ride.rideSec).isEqualTo(270.0)
    }

    @Test
    fun irregularHeadwaysWaitLongerThanHalfTheMean() {
        assertThat(Headway.fixed(10).expectedWaitSec(0.0)).isEqualTo(300.0)
        assertThat(Headway(5, 15).expectedWaitSec(0.0)).isCloseTo(325.0, within(1e-9))
        assertThat(Headway.fixed(10).expectedWaitSec(0.3)).isCloseTo(327.0, within(1e-9))
    }

    @Test
    fun loopLineRidesThroughItsEndWithoutWaitingAgain() {
        val loop =
            pattern(
                10,
                "순환",
                5,
                listOf(stop(400, 10, 60), stop(401, 11, 60), stop(402, 12, 60), stop(403, 10, null)),
            )

        val result = route(graph, TransitNetwork(listOf(loop)), 12, 11)!!

        val ride = result.legs.filterIsInstance<TransitLeg.Ride>().single()
        assertThat(ride.stopCount).isEqualTo(2)
        assertThat(ride.rideSec).isEqualTo(120.0)
        assertThat(result.waitSec).isEqualTo(150.0)
        assertThat(ride.stopPoints).hasSize(3)
    }

    @Test
    fun returnsNullWithoutAnyLine() {
        assertThat(route(graph, TransitNetwork(emptyList()), 1, 2)).isNull()
    }
}
