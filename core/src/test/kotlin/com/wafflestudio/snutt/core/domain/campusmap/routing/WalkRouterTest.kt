package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class WalkRouterTest {
    private fun route(
        graph: WalkGraph,
        from: List<Long>,
        to: List<Long>,
        allowIndoor: Boolean = true,
    ) = WalkRouter.route(graph, from.map { graph.at(it) }, to.map { graph.at(it) }, allowIndoor)

    private fun visited(
        graph: WalkGraph,
        route: WalkRoute,
    ): List<Long> = listOf(graph.nodeId(route.start)) + route.steps.map { graph.nodeId(graph.end(it.edge, it.forward)) }

    @Test
    fun flatPathUsesToblerWalkingSpeed() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0)
                    node(2, 100.0)
                    edge(1, 2)
                }.build()

        val result = route(graph, listOf(1), listOf(2))!!

        assertThat(result.distanceM).isCloseTo(100.0, within(0.01))
        assertThat(result.timeSec).isCloseTo(71.475, within(0.01))
    }

    @Test
    fun uphillTakesAtLeastAsLongAsDownhill() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0, elevationM = 100.0)
                    node(2, 100.0, elevationM = 110.0)
                    node(3, 0.0, eastM = 50.0, elevationM = 100.0)
                    node(4, 30.0, eastM = 50.0, elevationM = 110.0)
                    edge(1, 2)
                    edge(3, 4, WalkEdgeKind.STEPS)
                }.build()

        val pathUp = route(graph, listOf(1), listOf(2))!!
        val pathDown = route(graph, listOf(2), listOf(1))!!
        val stepsUp = route(graph, listOf(3), listOf(4))!!
        val stepsDown = route(graph, listOf(4), listOf(3))!!

        assertThat(pathUp.timeSec).isGreaterThan(pathDown.timeSec)
        assertThat(pathUp.ascentM).isCloseTo(10.0, within(1e-9))
        assertThat(pathDown.descentM).isCloseTo(10.0, within(1e-9))
        assertThat(stepsUp.timeSec).isCloseTo(10 / 0.3, within(0.01))
        assertThat(stepsDown.timeSec).isCloseTo(30.0, within(0.01))
    }

    @Test
    fun indoorPassagesCanBeExcluded() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0)
                    node(2, 50.0)
                    node(3, 0.0, eastM = 80.0)
                    node(4, 50.0, eastM = 80.0)
                    edge(1, 2, WalkEdgeKind.INDOOR)
                    edge(1, 3)
                    edge(3, 4)
                    edge(4, 2)
                }.build()

        val through = route(graph, listOf(1), listOf(2))!!
        val around = route(graph, listOf(1), listOf(2), allowIndoor = false)!!

        assertThat(visited(graph, through)).containsExactly(1L, 2L)
        assertThat(visited(graph, around)).containsExactly(1L, 3L, 4L, 2L)
        assertThat(around.indoorM).isZero()
    }

    @Test
    fun returnsNullWhenOnlyIndoorPassageConnectsAndIndoorIsExcluded() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0)
                    node(2, 50.0)
                    edge(1, 2, WalkEdgeKind.INDOOR)
                }.build()

        assertThat(route(graph, listOf(1), listOf(2), allowIndoor = false)).isNull()
    }

    @Test
    fun startsFromAnySourceAndStopsAtNearestTarget() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0)
                    node(2, 100.0)
                    node(3, 150.0)
                    node(4, 300.0)
                    edge(1, 2)
                    edge(2, 3)
                    edge(3, 4)
                }.build()

        val result = route(graph, listOf(1, 2), listOf(3, 4))!!

        assertThat(graph.nodeId(result.start)).isEqualTo(2L)
        assertThat(graph.nodeId(result.end)).isEqualTo(3L)
        assertThat(result.distanceM).isCloseTo(50.0, within(0.01))
    }

    @Test
    fun summaryCountsFlightsAndSplitsSegmentsByKindAndEstimation() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0, elevationM = 100.0)
                    node(2, 50.0, elevationM = 100.0)
                    node(3, 60.0, elevationM = 103.0)
                    node(4, 70.0, elevationM = 106.0)
                    node(5, 120.0, elevationM = 106.0)
                    node(6, 150.0, elevationM = 106.0)
                    edge(1, 2)
                    edge(2, 3, WalkEdgeKind.STEPS)
                    edge(3, 4, WalkEdgeKind.STEPS)
                    edge(4, 5)
                    edge(5, 6, WalkEdgeKind.INDOOR, covered = true, estimated = true)
                }.build()

        val result = route(graph, listOf(1), listOf(6))!!

        assertThat(result.stairFlights).isEqualTo(1)
        assertThat(result.stairsM).isCloseTo(20.0, within(0.01))
        assertThat(result.indoorM).isCloseTo(30.0 * 1.3, within(0.01))
        assertThat(result.estimatedM).isCloseTo(30.0 * 1.3, within(0.01))
        assertThat(result.ascentM).isCloseTo(6.0, within(1e-9))
        assertThat(result.segments.map { it.kind to it.estimated })
            .containsExactly(
                WalkEdgeKind.PATH to false,
                WalkEdgeKind.STEPS to false,
                WalkEdgeKind.PATH to false,
                WalkEdgeKind.INDOOR to true,
            )
        assertThat(result.segments.sumOf { it.points.size - 1 }).isEqualTo(result.steps.size)
        assertThat(result.points).hasSize(result.steps.size + 1)
        assertThat(result.elevationProfile.map { it.elevationM }).containsExactly(100.0, 100.0, 103.0, 106.0, 106.0, 106.0)
    }

    @Test
    fun returnsNullWhenUnreachable() {
        val graph =
            TestGraph()
                .apply {
                    node(1, 0.0)
                    node(2, 100.0)
                    node(3, 200.0)
                    edge(1, 2)
                }.build()

        assertThat(route(graph, listOf(1), listOf(3))).isNull()
        assertThat(route(graph, emptyList(), listOf(2))).isNull()
    }
}
