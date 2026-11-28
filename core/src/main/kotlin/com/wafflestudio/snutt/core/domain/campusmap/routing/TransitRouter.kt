package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind

data class RideLine(
    val lineId: Long,
    val lineName: String,
    val rideSec: Double,
    val stopCount: Int,
)

sealed interface TransitLeg {
    data class Walk(
        val route: WalkRoute,
    ) : TransitLeg

    data class Ride(
        val lines: List<RideLine>,
        val from: TransitPatternStop,
        val to: TransitPatternStop,
        val stopCount: Int,
        val waitSec: Double,
        val rideSec: Double,
        val distanceM: Double,
        val path: List<GeoCoordinate>,
        val stopPoints: List<GeoCoordinate>,
    ) : TransitLeg
}

data class TransitRoute(
    val timeSec: Double,
    val walkSec: Double,
    val waitSec: Double,
    val rideSec: Double,
    val walkDistanceM: Double,
    val legs: List<TransitLeg>,
)

object TransitRouter {
    private const val NONE = -1

    fun route(
        graph: WalkGraph,
        network: TransitNetwork,
        sources: Collection<Int>,
        targets: Collection<Int>,
        walkWeight: Double = 1.0,
        allowIndoor: Boolean = true,
    ): TransitRoute? {
        if (sources.isEmpty() || targets.isEmpty() || network.hops.isEmpty()) return null
        val n = graph.nodeCount
        val isTarget = BooleanArray(n)
        targets.forEach { isTarget[it] = true }
        val cost = DoubleArray(2 * n) { Double.POSITIVE_INFINITY }
        val prev = IntArray(2 * n) { NONE }
        val viaEdge = IntArray(2 * n) { NONE }
        val viaHop = IntArray(2 * n) { NONE }
        val heap = CostHeap()

        sources.forEach {
            cost[it] = 0.0
            heap.push(0.0, it)
        }
        var end = NONE
        while (heap.isNotEmpty()) {
            val c = heap.peekCost()
            val u = heap.pop()
            if (c > cost[u]) continue
            val layer = if (u < n) 0 else n
            val w = u - layer
            if (layer == n && isTarget[w]) {
                end = u
                break
            }
            graph.forEachEdge(w) { edge ->
                if (!allowIndoor && graph.kind(edge) == WalkEdgeKind.INDOOR) return@forEachEdge
                val forward = graph.isForward(edge, w)
                val v = layer + graph.end(edge, forward)
                val time = graph.edgeTimeSec(edge, forward)
                val next = c + time * walkWeight
                if (next < cost[v]) {
                    cost[v] = next
                    prev[v] = u
                    viaEdge[v] = edge
                    viaHop[v] = NONE
                    heap.push(next, v)
                }
            }
            for (h in network.hopsFrom(w)) {
                val hop = network.hops[h]
                val v = n + hop.toNode
                val next = c + hop.choice.timeSec
                if (next < cost[v]) {
                    cost[v] = next
                    prev[v] = u
                    viaEdge[v] = NONE
                    viaHop[v] = h
                    heap.push(next, v)
                }
            }
        }
        if (end == NONE) return null

        val chain = ArrayList<Int>()
        var u = end
        while (u != NONE) {
            chain.add(u)
            u = prev[u]
        }
        chain.reverse()

        val legs = ArrayList<TransitLeg>()
        var walkStart = chain[0] % n
        var walkSteps = ArrayList<WalkStep>()
        for (k in 1 until chain.size) {
            val state = chain[k]
            val node = state % n
            if (viaHop[state] != NONE) {
                if (walkSteps.isNotEmpty()) legs.add(TransitLeg.Walk(WalkRouter.summarize(graph, walkStart, walkSteps)))
                walkSteps = ArrayList()
                legs.add(rideLeg(graph, network.hops[viaHop[state]].choice))
                walkStart = node
            } else {
                val edge = viaEdge[state]
                walkSteps.add(WalkStep(edge, graph.end(edge, true) == node))
            }
        }
        if (walkSteps.isNotEmpty()) legs.add(TransitLeg.Walk(WalkRouter.summarize(graph, walkStart, walkSteps)))

        val walks = legs.filterIsInstance<TransitLeg.Walk>()
        val rides = legs.filterIsInstance<TransitLeg.Ride>()
        val walkSec = walks.sumOf { it.route.timeSec }
        val waitSec = rides.sumOf { it.waitSec }
        val rideSec = rides.sumOf { it.rideSec }
        return TransitRoute(
            timeSec = walkSec + waitSec + rideSec,
            walkSec = walkSec,
            waitSec = waitSec,
            rideSec = rideSec,
            walkDistanceM = walks.sumOf { it.route.distanceM },
            legs = legs,
        )
    }

    private fun rideLeg(
        graph: WalkGraph,
        picked: HopChoice,
    ): TransitLeg.Ride {
        val stops = picked.options.first().stops
        val path = ArrayList<GeoCoordinate>()
        stops.dropLast(1).forEachIndexed { i, stop -> path.addAll(if (i == 0) stop.pathToNext else stop.pathToNext.drop(1)) }
        return TransitLeg.Ride(
            lines = picked.options.map { RideLine(it.pattern.lineId, it.pattern.lineName, it.rideSec, it.stopCount) },
            from = stops.first(),
            to = stops.last(),
            stopCount = stops.size - 1,
            waitSec = picked.waitSec,
            rideSec = picked.rideSec,
            distanceM = stops.dropLast(1).sumOf { (it.roadMToNext ?: 0).toDouble() },
            path = path,
            stopPoints = stops.map { graph.coordinate(it.node) },
        )
    }
}
