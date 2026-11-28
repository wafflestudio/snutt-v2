package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind

object WalkRouter {
    fun route(
        graph: WalkGraph,
        sources: Collection<Int>,
        targets: Collection<Int>,
        allowIndoor: Boolean = true,
    ): WalkRoute? {
        if (sources.isEmpty() || targets.isEmpty()) return null
        val isTarget = BooleanArray(graph.nodeCount)
        targets.forEach { isTarget[it] = true }
        val cost = DoubleArray(graph.nodeCount) { Double.POSITIVE_INFINITY }
        val prevEdge = IntArray(graph.nodeCount) { -1 }
        val heap = CostHeap()
        sources.forEach {
            cost[it] = 0.0
            heap.push(0.0, it)
        }
        var end = -1
        while (heap.isNotEmpty()) {
            val c = heap.peekCost()
            val u = heap.pop()
            if (c > cost[u]) continue
            if (isTarget[u]) {
                end = u
                break
            }
            graph.forEachEdge(u) { edge ->
                if (!allowIndoor && graph.kind(edge) == WalkEdgeKind.INDOOR) return@forEachEdge
                val forward = graph.isForward(edge, u)
                val v = graph.end(edge, forward)
                val next = c + graph.edgeTimeSec(edge, forward)
                if (next < cost[v]) {
                    cost[v] = next
                    prevEdge[v] = edge
                    heap.push(next, v)
                }
            }
        }
        if (end < 0) return null
        val steps = ArrayList<WalkStep>()
        var u = end
        while (prevEdge[u] >= 0) {
            val edge = prevEdge[u]
            val forward = graph.end(edge, true) == u
            steps.add(WalkStep(edge, forward))
            u = graph.start(edge, forward)
        }
        steps.reverse()
        return summarize(graph, u, steps)
    }

    fun summarize(
        graph: WalkGraph,
        start: Int,
        steps: List<WalkStep>,
    ): WalkRoute {
        var distance = 0.0
        var time = 0.0
        var ascent = 0.0
        var descent = 0.0
        var stairs = 0.0
        var flights = 0
        var indoor = 0.0
        var covered = 0.0
        var estimated = 0.0
        val points = ArrayList<GeoCoordinate>(steps.size + 1).apply { add(graph.coordinate(start)) }
        val profile = ArrayList<ElevationPoint>(steps.size + 1)
        val segments = ArrayList<WalkSegment>()
        var segmentPoints = mutableListOf(graph.coordinate(start))
        var previousKind: WalkEdgeKind? = null
        var previousEstimated = false
        var end = start
        for ((index, step) in steps.withIndex()) {
            val edge = step.edge
            val kind = graph.kind(edge)
            val length = graph.length(edge)
            val from = graph.start(edge, step.forward)
            end = graph.end(edge, step.forward)
            if (index == 0) profile.add(ElevationPoint(0.0, graph.elevation(from), kind))
            distance += length
            time += graph.edgeTimeSec(edge, step.forward)
            val dh = graph.climb(edge, step.forward)
            if (dh > 0) ascent += dh else descent -= dh
            if (kind == WalkEdgeKind.STEPS) {
                stairs += length
                if (previousKind != WalkEdgeKind.STEPS) flights++
            }
            if (kind == WalkEdgeKind.INDOOR) indoor += length
            if (graph.isCovered(edge)) covered += length
            if (graph.isEstimated(edge)) estimated += length
            if (index > 0 && (kind != previousKind || graph.isEstimated(edge) != previousEstimated)) {
                segments.add(WalkSegment(previousKind!!, previousEstimated, segmentPoints))
                segmentPoints = mutableListOf(graph.coordinate(from))
            }
            segmentPoints.add(graph.coordinate(end))
            previousKind = kind
            previousEstimated = graph.isEstimated(edge)
            profile.add(ElevationPoint(distance, graph.elevation(end), kind))
            points.add(graph.coordinate(end))
        }
        if (previousKind != null) segments.add(WalkSegment(previousKind, previousEstimated, segmentPoints))
        return WalkRoute(
            start = start,
            end = end,
            steps = steps,
            distanceM = distance,
            timeSec = time,
            ascentM = ascent,
            descentM = descent,
            stairsM = stairs,
            stairFlights = flights,
            indoorM = indoor,
            coveredM = covered,
            estimatedM = estimated,
            points = points,
            segments = segments,
            elevationProfile = profile,
        )
    }
}
