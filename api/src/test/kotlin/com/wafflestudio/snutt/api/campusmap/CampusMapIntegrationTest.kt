package com.wafflestudio.snutt.api.campusmap

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.campusmap.model.ElevationSource
import com.wafflestudio.snutt.core.domain.campusmap.model.MapAccessPoint
import com.wafflestudio.snutt.core.domain.campusmap.model.MapBuilding
import com.wafflestudio.snutt.core.domain.campusmap.model.MapEntrance
import com.wafflestudio.snutt.core.domain.campusmap.model.MapSource
import com.wafflestudio.snutt.core.domain.campusmap.model.MapStructure
import com.wafflestudio.snutt.core.domain.campusmap.model.MapTransitLine
import com.wafflestudio.snutt.core.domain.campusmap.model.MapTransitLineStop
import com.wafflestudio.snutt.core.domain.campusmap.model.MapTransitStop
import com.wafflestudio.snutt.core.domain.campusmap.model.MapWalkEdge
import com.wafflestudio.snutt.core.domain.campusmap.model.MapWalkNode
import com.wafflestudio.snutt.core.domain.campusmap.model.TransitLineKind
import com.wafflestudio.snutt.core.domain.campusmap.model.WalkEdgeKind
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapAccessPointRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapBuildingRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapEntranceRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapStructureRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapTransitLineRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapTransitLineStopRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapTransitStopRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapWalkEdgeRepository
import com.wafflestudio.snutt.core.domain.campusmap.repository.MapWalkNodeRepository
import com.wafflestudio.snutt.core.domain.campusmap.routing.TransitLeg
import com.wafflestudio.snutt.core.domain.campusmap.service.CampusMapProvider
import com.wafflestudio.snutt.core.domain.campusmap.service.CampusRouteService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.math.cos

class CampusMapIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired lateinit var nodeRepository: MapWalkNodeRepository

    @Autowired lateinit var edgeRepository: MapWalkEdgeRepository

    @Autowired lateinit var structureRepository: MapStructureRepository

    @Autowired lateinit var buildingRepository: MapBuildingRepository

    @Autowired lateinit var entranceRepository: MapEntranceRepository

    @Autowired lateinit var accessPointRepository: MapAccessPointRepository

    @Autowired lateinit var stopRepository: MapTransitStopRepository

    @Autowired lateinit var lineRepository: MapTransitLineRepository

    @Autowired lateinit var lineStopRepository: MapTransitLineStopRepository

    @Autowired lateinit var provider: CampusMapProvider

    @Autowired lateinit var routeService: CampusRouteService

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val first = PlaceInfo(Campus.GWANAK, "1")
    private val second = PlaceInfo(Campus.GWANAK, "2")

    private fun node(
        northM: Double,
        eastM: Double = 0.0,
    ): MapWalkNode =
        nodeRepository.save(
            MapWalkNode(
                latitude = 37.46 + northM / 111194.93,
                longitude = 126.95 + eastM / (111194.93 * cos(Math.toRadians(37.46))),
                elevationM = 100.0,
                elevationSource = ElevationSource.DEM,
                source = MapSource.INITIAL,
            ),
        )

    private fun edge(
        a: MapWalkNode,
        b: MapWalkNode,
        kind: WalkEdgeKind = WalkEdgeKind.PATH,
        blocked: Boolean = false,
    ): MapWalkEdge {
        val (low, high) = listOf(a.id!!, b.id!!).sorted()
        return edgeRepository.save(MapWalkEdge(low, high, kind, isBlocked = blocked, source = MapSource.INITIAL))
    }

    private fun version(): Long = jdbcTemplate.queryForObject("SELECT version FROM map_state WHERE id = 1", Long::class.java)!!

    private fun bumpVersion(): Long {
        jdbcTemplate.update("UPDATE map_state SET version = version + 1 WHERE id = 1")
        return version()
    }

    private fun seedMap(): Map<String, MapWalkNode> {
        val entrance = node(0.0)
        val middle = node(500.0, eastM = 300.0)
        val far = node(1000.0)
        val unconnected = node(-30.0)
        edge(entrance, middle)
        edge(middle, far)
        edge(entrance, far, blocked = true)

        val structure =
            structureRepository.save(
                MapStructure("인문관", listOf(GeoCoordinate(37.46, 126.95)), source = MapSource.INITIAL),
            )
        buildingRepository.save(
            MapBuilding("1", Campus.GWANAK, "인문관1", "Humanities 1", 37.46, 126.95, structure.id, MapSource.INITIAL),
        )
        entranceRepository.save(
            MapEntrance(structure.id!!, entrance.id!!, isWheelchairAccessible = true, source = MapSource.INITIAL),
        )
        entranceRepository.save(MapEntrance(structure.id!!, unconnected.id!!, source = MapSource.INITIAL))
        val secondBuilding =
            buildingRepository.save(MapBuilding("2", Campus.GWANAK, "인문관2", "", 37.47, 126.95, null, MapSource.INITIAL))
        accessPointRepository.save(MapAccessPoint(secondBuilding.id!!, far.id!!))

        val stopA = stopRepository.save(MapTransitStop("정문", "21299", 37.46, 126.95, entrance.id!!, MapSource.INITIAL))
        val stopB = stopRepository.save(MapTransitStop("후문", null, 37.47, 126.95, far.id!!, MapSource.INITIAL))
        val line = lineRepository.save(MapTransitLine("셔틀", TransitLineKind.SHUTTLE, 6, 6, "6분", false, MapSource.INITIAL))
        lineStopRepository.save(
            MapTransitLineStop(
                line.id!!,
                0,
                0,
                stopA.id!!,
                "정문",
                60,
                roadMToNext = 1000,
                pathToNext = listOf(GeoCoordinate(37.46, 126.95), GeoCoordinate(37.47, 126.95)),
            ),
        )
        lineStopRepository.save(MapTransitLineStop(line.id!!, 0, 1, stopB.id!!))
        return mapOf("entrance" to entrance, "middle" to middle, "far" to far)
    }

    @Test
    fun mapStateRowSurvivesTableCleanup() {
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM map_state", Int::class.java))
    }

    @Test
    fun loadsMapAndRoutesBetweenBuildings() {
        val nodes = seedMap()
        val version = bumpVersion()
        val map = provider.refresh()

        assertEquals(version, map.version)
        assertEquals(4, map.graph.nodeCount)
        assertEquals(2, map.graph.edgeCount)

        val firstBuilding = map.building(first)!!
        assertFalse(firstBuilding.accessEstimated)
        assertEquals(listOf(nodes.getValue("entrance").id), firstBuilding.accessNodes.map { map.graph.nodeId(it) })
        val secondBuilding = map.building(second)!!
        assertTrue(secondBuilding.accessEstimated)
        assertEquals(listOf(nodes.getValue("far").id), secondBuilding.accessNodes.map { map.graph.nodeId(it) })

        val routes = routeService.routes(first, second)!!
        val walk = routes.walk!!
        assertEquals(1166.2, walk.distanceM, 1.0)
        assertEquals(2, walk.steps.size)

        val transit = routes.fastestTransit!!
        val ride = transit.legs.single() as TransitLeg.Ride
        assertEquals(listOf("셔틀"), ride.lines.map { it.lineName })
        assertEquals("정문", ride.from.displayName)
        assertEquals(187.2 + 60.0, transit.timeSec, 1e-9)

        assertEquals(transit, routes.leastWalkingTransit)
        assertNull(routeService.routes(first, PlaceInfo(Campus.GWANAK, "없는동")))
    }

    @Test
    fun reloadsOnlyAfterVersionChanges() {
        seedMap()
        val loaded = bumpVersion()
        assertEquals(2, provider.refresh().graph.edgeCount)

        val shortcut = edgeRepository.findAll().single { it.isBlocked }
        shortcut.isBlocked = false
        edgeRepository.save(shortcut)
        assertEquals(2, provider.refresh().graph.edgeCount)

        bumpVersion()
        val map = provider.refresh()
        assertEquals(loaded + 1, map.version)
        assertEquals(3, map.graph.edgeCount)
        assertEquals(1000.0, routeService.routes(first, second)!!.walk!!.distanceM, 1.0)
    }

    @Test
    fun dropsTransitRoutesSlowerThanWalking() {
        seedMap()
        val line = lineRepository.findAll().single()
        line.minHeadwayMin = 60
        line.maxHeadwayMin = 60
        lineRepository.save(line)
        bumpVersion()
        provider.refresh()

        val routes = routeService.routes(first, second)!!

        assertTrue(routes.walk!!.timeSec < 1872.0 + 60.0)
        assertNull(routes.fastestTransit)
        assertNull(routes.leastWalkingTransit)
    }

    @Test
    fun sameBuildingNumberOnAnotherCampusIsSeparateBuilding() {
        seedMap()
        val yeongeon =
            buildingRepository.save(MapBuilding("1", Campus.YEONGEON, "연건1", "", 37.58, 127.0, null, MapSource.INITIAL))
        bumpVersion()
        val map = provider.refresh()

        assertEquals(yeongeon.id, map.building(PlaceInfo(Campus.YEONGEON, "1"))!!.buildingId)
        assertNotEquals(yeongeon.id, map.building(first)!!.buildingId)
        assertThrows<DataAccessException> {
            buildingRepository.save(MapBuilding("1", Campus.YEONGEON, "중복", "", 37.58, 127.0, null, MapSource.INITIAL))
        }
    }

    @Test
    fun edgeKeepsNodeOrderAndIsUniquePerKind() {
        val nodes = seedMap()
        val entrance = nodes.getValue("entrance").id!!
        val middle = nodes.getValue("middle").id!!

        assertThrows<DataAccessException> {
            edgeRepository.save(MapWalkEdge(middle, entrance, WalkEdgeKind.STEPS, source = MapSource.INITIAL))
        }
        assertThrows<DataAccessException> {
            edgeRepository.save(MapWalkEdge(entrance, middle, WalkEdgeKind.PATH, source = MapSource.INITIAL))
        }
        edgeRepository.save(MapWalkEdge(entrance, middle, WalkEdgeKind.STEPS, source = MapSource.INITIAL))
    }
}
