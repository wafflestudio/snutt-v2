package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class WalkGraph private constructor(
    private val nodeIds: LongArray,
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val elevations: DoubleArray,
    private val edgeIds: LongArray,
    private val edgeA: IntArray,
    private val edgeB: IntArray,
    private val kinds: Array<WalkEdgeKind>,
    private val covered: BooleanArray,
    private val estimated: BooleanArray,
    private val lengths: DoubleArray,
    private val adjacencyStart: IntArray,
    private val adjacency: IntArray,
) {
    data class Node(
        val id: Long,
        val latitude: Double,
        val longitude: Double,
        val elevationM: Double,
    )

    data class Edge(
        val id: Long,
        val nodeAId: Long,
        val nodeBId: Long,
        val kind: WalkEdgeKind,
        val covered: Boolean,
        val estimated: Boolean,
    )

    private val indexById: Map<Long, Int> = nodeIds.withIndex().associate { (index, id) -> id to index }

    val nodeCount: Int get() = nodeIds.size

    val edgeCount: Int get() = edgeIds.size

    fun indexOf(nodeId: Long): Int? = indexById[nodeId]

    fun nodeId(node: Int): Long = nodeIds[node]

    fun edgeId(edge: Int): Long = edgeIds[edge]

    fun coordinate(node: Int): GeoCoordinate = GeoCoordinate(latitudes[node], longitudes[node])

    fun elevation(node: Int): Double = elevations[node]

    fun kind(edge: Int): WalkEdgeKind = kinds[edge]

    fun isCovered(edge: Int): Boolean = covered[edge]

    fun isEstimated(edge: Int): Boolean = estimated[edge]

    fun length(edge: Int): Double = lengths[edge]

    fun hasEdges(node: Int): Boolean = adjacencyStart[node] < adjacencyStart[node + 1]

    inline fun forEachEdge(
        node: Int,
        action: (edge: Int) -> Unit,
    ) {
        for (k in edgeRangeStart(node) until edgeRangeEnd(node)) action(edgeAt(k))
    }

    @PublishedApi
    internal fun edgeRangeStart(node: Int): Int = adjacencyStart[node]

    @PublishedApi
    internal fun edgeRangeEnd(node: Int): Int = adjacencyStart[node + 1]

    @PublishedApi
    internal fun edgeAt(k: Int): Int = adjacency[k]

    fun isForward(
        edge: Int,
        from: Int,
    ): Boolean = edgeA[edge] == from

    fun start(
        edge: Int,
        forward: Boolean,
    ): Int = if (forward) edgeA[edge] else edgeB[edge]

    fun end(
        edge: Int,
        forward: Boolean,
    ): Int = if (forward) edgeB[edge] else edgeA[edge]

    fun climb(
        edge: Int,
        forward: Boolean,
    ): Double = elevations[end(edge, forward)] - elevations[start(edge, forward)]

    fun edgeTimeSec(
        edge: Int,
        forward: Boolean,
    ): Double {
        val length = lengths[edge]
        val dh = climb(edge, forward)
        val kind = kinds[edge]
        if (kind == WalkEdgeKind.STEPS || kind == WalkEdgeKind.INDOOR) {
            val horizontal = length / (if (kind == WalkEdgeKind.STEPS) STEPS_SPEED else INDOOR_SPEED)
            val vertical = if (dh > 0) dh / VERTICAL_UP_SPEED else -dh / VERTICAL_DOWN_SPEED
            return max(horizontal, vertical)
        }
        if (length < 0.5) return length / FLAT_SPEED
        val slope = max(-0.5, min(0.5, dh / length))
        return length / (6 * exp(-3.5 * abs(slope + 0.05)) / 3.6)
    }

    companion object {
        private const val FLAT_SPEED = 1.4
        private const val STEPS_SPEED = 1.0
        private const val INDOOR_SPEED = 1.2
        private const val VERTICAL_UP_SPEED = 0.3
        private const val VERTICAL_DOWN_SPEED = 0.4

        private const val INDOOR_DETOUR = 1.3
        private const val EARTH_RADIUS_M = 6371000.0

        fun of(
            nodes: List<Node>,
            edges: List<Edge>,
        ): WalkGraph {
            val indexById = HashMap<Long, Int>(nodes.size * 2)
            nodes.forEachIndexed { index, node -> require(indexById.put(node.id, index) == null) { "정점 id 중복: ${node.id}" } }
            val edgeA = IntArray(edges.size)
            val edgeB = IntArray(edges.size)
            val lengths = DoubleArray(edges.size)
            edges.forEachIndexed { i, edge ->
                val a = requireNotNull(indexById[edge.nodeAId]) { "간선 ${edge.id}의 정점 ${edge.nodeAId}이 없다" }
                val b = requireNotNull(indexById[edge.nodeBId]) { "간선 ${edge.id}의 정점 ${edge.nodeBId}이 없다" }
                edgeA[i] = a
                edgeB[i] = b
                val straight = distanceM(nodes[a].latitude, nodes[a].longitude, nodes[b].latitude, nodes[b].longitude)
                lengths[i] = if (edge.kind == WalkEdgeKind.INDOOR) straight * INDOOR_DETOUR else straight
            }
            val degree = IntArray(nodes.size + 1)
            for (i in edges.indices) {
                degree[edgeA[i] + 1]++
                degree[edgeB[i] + 1]++
            }
            for (v in 1..nodes.size) degree[v] += degree[v - 1]
            val adjacencyStart = degree.copyOf()
            val cursor = degree.copyOf()
            val adjacency = IntArray(edges.size * 2)
            for (i in edges.indices) {
                adjacency[cursor[edgeA[i]]++] = i
                adjacency[cursor[edgeB[i]]++] = i
            }
            return WalkGraph(
                nodeIds = LongArray(nodes.size) { nodes[it].id },
                latitudes = DoubleArray(nodes.size) { nodes[it].latitude },
                longitudes = DoubleArray(nodes.size) { nodes[it].longitude },
                elevations = DoubleArray(nodes.size) { nodes[it].elevationM },
                edgeIds = LongArray(edges.size) { edges[it].id },
                edgeA = edgeA,
                edgeB = edgeB,
                kinds = Array(edges.size) { edges[it].kind },
                covered = BooleanArray(edges.size) { edges[it].covered },
                estimated = BooleanArray(edges.size) { edges[it].estimated },
                lengths = lengths,
                adjacencyStart = adjacencyStart,
                adjacency = adjacency,
            )
        }

        fun distanceM(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val r = PI / 180
            val x = (lon2 - lon1) * r * cos((lat1 + lat2) / 2 * r)
            val y = (lat2 - lat1) * r
            return sqrt(x * x + y * y) * EARTH_RADIUS_M
        }
    }
}
