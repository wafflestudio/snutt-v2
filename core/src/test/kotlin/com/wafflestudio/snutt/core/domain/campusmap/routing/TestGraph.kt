package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind
import kotlin.math.PI
import kotlin.math.cos

class TestGraph {
    private val nodes = mutableListOf<WalkGraph.Node>()
    private val edges = mutableListOf<WalkGraph.Edge>()

    fun node(
        id: Long,
        northM: Double,
        eastM: Double = 0.0,
        elevationM: Double = 100.0,
    ) {
        nodes += WalkGraph.Node(id, BASE_LAT + northM / M_PER_DEG, BASE_LON + eastM / (M_PER_DEG * cos(BASE_LAT * PI / 180)), elevationM)
    }

    fun edge(
        a: Long,
        b: Long,
        kind: WalkEdgeKind = WalkEdgeKind.PATH,
        covered: Boolean = false,
        estimated: Boolean = false,
    ) {
        edges += WalkGraph.Edge(edges.size + 1L, a, b, kind, covered, estimated)
    }

    fun build(): WalkGraph = WalkGraph.of(nodes, edges)

    companion object {
        const val BASE_LAT = 37.46
        const val BASE_LON = 126.95
        const val M_PER_DEG = PI / 180 * 6371000
    }
}

fun WalkGraph.at(id: Long): Int = indexOf(id)!!
