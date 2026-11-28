package com.wafflestudio.snutt.core.domain.campusmap.service

import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapAccessPointRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapBuildingRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapEntranceRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapStateRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapTransitLineRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapTransitLineStopRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapTransitStopRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapWalkEdgeRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapWalkNodeRepository
import com.wafflestudio.snutt.core.domain.campusmap.routing.CampusMap
import com.wafflestudio.snutt.core.domain.campusmap.routing.CampusMapBuilding
import com.wafflestudio.snutt.core.domain.campusmap.routing.Headway
import com.wafflestudio.snutt.core.domain.campusmap.routing.TransitNetwork
import com.wafflestudio.snutt.core.domain.campusmap.routing.TransitPattern
import com.wafflestudio.snutt.core.domain.campusmap.routing.TransitPatternStop
import com.wafflestudio.snutt.core.domain.campusmap.routing.WalkGraph
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CampusMapLoader(
    private val stateRepository: MapStateRepository,
    private val nodeRepository: MapWalkNodeRepository,
    private val edgeRepository: MapWalkEdgeRepository,
    private val buildingRepository: MapBuildingRepository,
    private val entranceRepository: MapEntranceRepository,
    private val accessPointRepository: MapAccessPointRepository,
    private val stopRepository: MapTransitStopRepository,
    private val lineRepository: MapTransitLineRepository,
    private val lineStopRepository: MapTransitLineStopRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun currentVersion(): Long = stateRepository.findById(STATE_ID).map { it.version }.orElse(0L)

    @Transactional(readOnly = true)
    fun load(): CampusMap {
        val version = currentVersion()
        val graph =
            WalkGraph.of(
                nodes =
                    nodeRepository.findAll().sortedBy { it.id }.map {
                        WalkGraph.Node(
                            it.id!!,
                            it.latitude,
                            it.longitude,
                            it.elevationM,
                        )
                    },
                edges =
                    edgeRepository
                        .findAll()
                        .filterNot { it.isBlocked }
                        .sortedBy { it.id }
                        .map { WalkGraph.Edge(it.id!!, it.nodeAId, it.nodeBId, it.kind, it.isCovered, it.isEstimated) },
            )
        return CampusMap(
            version = version,
            graph = graph,
            buildings = loadBuildings(graph),
            transit = loadTransit(graph),
        )
    }

    private fun loadBuildings(graph: WalkGraph): Map<PlaceInfo, CampusMapBuilding> {
        val entrances = entranceRepository.findAll().groupBy({ it.structureId }, { it.nodeId })
        val accessPoints = accessPointRepository.findAll().groupBy({ it.buildingId }, { it.nodeId })
        return buildingRepository.findAll().associate { building ->
            val connected =
                building.structureId
                    ?.let { entrances[it] }
                    .orEmpty()
                    .mapNotNull { graph.indexOf(it) }
                    .filter { graph.hasEdges(it) }
                    .distinct()
            val estimated =
                accessPoints[building.id]
                    .orEmpty()
                    .mapNotNull { graph.indexOf(it) }
                    .distinct()
            PlaceInfo(building.campus, building.buildingNumber) to
                CampusMapBuilding(
                    buildingId = building.id!!,
                    campus = building.campus,
                    buildingNumber = building.buildingNumber,
                    nameKor = building.nameKor,
                    nameEng = building.nameEng,
                    location = GeoCoordinate(building.latitude, building.longitude),
                    accessNodes = connected.ifEmpty { estimated },
                    accessEstimated = connected.isEmpty(),
                )
        }
    }

    private fun loadTransit(graph: WalkGraph): TransitNetwork {
        val stops = stopRepository.findAll().associateBy { it.id!! }
        val lineStops = lineStopRepository.findAll().groupBy { it.lineId }
        val patterns =
            lineRepository.findAll().sortedBy { it.id }.flatMap { line ->
                lineStops[line.id]
                    .orEmpty()
                    .groupBy { it.runNo }
                    .toSortedMap()
                    .mapNotNull { (runNo, rows) ->
                        val ordered = rows.sortedBy { it.seq }
                        val patternStops =
                            ordered.map { row ->
                                val stop = stops.getValue(row.stopId)
                                val node = graph.indexOf(stop.nodeId)
                                if (node == null || !graph.hasEdges(node)) {
                                    log.warn("노선 {} 운행 구간 {}: 정류장 {}의 정점이 길과 연결되지 않아 노선을 뺀다", line.name, runNo, stop.name)
                                    return@mapNotNull null
                                }
                                TransitPatternStop(
                                    stopId = stop.id!!,
                                    stopName = stop.name,
                                    displayName = row.displayName ?: stop.name,
                                    node = node,
                                    rideSecToNext = row.rideSecToNext,
                                    roadMToNext = row.roadMToNext,
                                    pathToNext = row.pathToNext.orEmpty(),
                                )
                            }
                        try {
                            TransitPattern(
                                line.id!!,
                                line.name,
                                Headway(line.minHeadwayMin, line.maxHeadwayMin),
                                line.kind.irregularity,
                                patternStops,
                            )
                        } catch (e: IllegalArgumentException) {
                            log.warn("노선 {} 운행 구간 {}을 뺀다: {}", line.name, runNo, e.message)
                            null
                        }
                    }
            }
        return TransitNetwork(patterns)
    }

    companion object {
        private const val STATE_ID = 1L
    }
}
