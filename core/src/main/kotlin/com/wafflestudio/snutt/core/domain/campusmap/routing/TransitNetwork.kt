package com.wafflestudio.snutt.core.domain.campusmap.routing

import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate

data class TransitPatternStop(
    val stopId: Long,
    val stopName: String,
    val displayName: String,
    val node: Int,
    val rideSecToNext: Int?,
    val roadMToNext: Int?,
    val pathToNext: List<GeoCoordinate>,
)

data class Headway(
    val minMin: Int,
    val maxMin: Int,
) {
    init {
        require(minMin in 1..maxMin) { "배차간격 범위가 잘못됐다: $minMin~$maxMin" }
    }

    fun expectedWaitSec(irregularity: Double): Double {
        val mean = (minMin + maxMin) / 2.0
        val spread = (maxMin - minMin).toDouble()
        val variance = spread * spread / 12 + (irregularity * mean) * (irregularity * mean)
        return (mean * mean + variance) / (2 * mean) * SECONDS_PER_MINUTE
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60.0

        fun fixed(minutes: Int) = Headway(minutes, minutes)
    }
}

data class TransitPattern(
    val lineId: Long,
    val lineName: String,
    val headway: Headway,
    val irregularity: Double,
    val stops: List<TransitPatternStop>,
) {
    val isLoop: Boolean = stops.size >= 3 && stops.first().node == stops.last().node

    val rideStops: List<TransitPatternStop> = if (isLoop) stops.dropLast(1) + stops else stops

    init {
        require(stops.size >= 2) { "$lineName: 정류장이 2개 이상이어야 한다" }
        require(stops.dropLast(1).all { it.rideSecToNext != null }) { "$lineName: 마지막이 아닌 정류장에 탑승 시간이 없다" }
        require(irregularity >= 0) { "$lineName: 불규칙성은 0 이상이어야 한다" }
    }

    val expectedWaitSec: Double = headway.expectedWaitSec(irregularity)

    fun rideSec(
        from: Int,
        to: Int,
    ): Double = (from until to).sumOf { rideStops[it].rideSecToNext!!.toDouble() }
}

data class RideOption(
    val pattern: TransitPattern,
    val fromIndex: Int,
    val toIndex: Int,
) {
    val rideSec: Double = pattern.rideSec(fromIndex, toIndex)
    val stopCount: Int get() = toIndex - fromIndex
    val stops: List<TransitPatternStop> get() = pattern.rideStops.subList(fromIndex, toIndex + 1)
}

data class HopChoice(
    val options: List<RideOption>,
    val waitSec: Double,
    val rideSec: Double,
) {
    val timeSec: Double get() = waitSec + rideSec
}

class TransitHop(
    val fromNode: Int,
    val toNode: Int,
    options: List<RideOption>,
) {
    val choice: HopChoice

    init {
        val byRide = options.sortedBy { it.rideSec }
        val singles = byRide.map { HopChoice(listOf(it), it.pattern.expectedWaitSec, it.rideSec) }
        val groups =
            (2..byRide.size).map { size ->
                val group = byRide.take(size)
                HopChoice(group, 1 / group.sumOf { 1 / it.pattern.expectedWaitSec }, group.last().rideSec)
            }
        choice = (singles + groups).minWith(compareBy<HopChoice> { it.timeSec }.thenByDescending { it.options.size })
    }
}

class TransitNetwork(
    val patterns: List<TransitPattern>,
) {
    val hops: List<TransitHop>
    private val hopsByNode: Map<Int, List<Int>>

    init {
        val best = LinkedHashMap<Pair<Int, Int>, LinkedHashMap<Long, RideOption>>()
        for (pattern in patterns) {
            val stops = pattern.rideStops
            val boardable = if (pattern.isLoop) pattern.stops.size - 1 else stops.size - 1
            for (i in 0 until boardable) {
                val last = if (pattern.isLoop) i + pattern.stops.size - 1 else stops.size - 1
                for (j in i + 1..last) {
                    val from = stops[i].node
                    val to = stops[j].node
                    if (from == to) continue
                    val option = RideOption(pattern, i, j)
                    val byLine = best.getOrPut(from to to) { LinkedHashMap() }
                    val current = byLine[pattern.lineId]
                    if (current == null || option.rideSec < current.rideSec) byLine[pattern.lineId] = option
                }
            }
        }
        hops = best.map { (key, byLine) -> TransitHop(key.first, key.second, byLine.values.toList()) }
        hopsByNode = hops.indices.groupBy { hops[it].fromNode }
    }

    fun hopsFrom(node: Int): List<Int> = hopsByNode[node].orEmpty()
}
