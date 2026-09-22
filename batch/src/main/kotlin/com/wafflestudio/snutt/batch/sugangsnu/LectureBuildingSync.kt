package com.wafflestudio.snutt.batch.sugangsnu

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.LectureBuilding
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.building.repository.LectureBuildingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

data class SnuMapSearchResult(
    @param:JsonProperty("search_list")
    val searchList: List<SnuMapSearchItem>,
)

data class SnuMapSearchItem(
    @param:JsonProperty("lat_val")
    val latitudeInDms: Double?,
    @param:JsonProperty("lon_val")
    val longitudeInDms: Double?,
    @param:JsonProperty("lat_val1")
    val latitudeInDecimal: Double?,
    @param:JsonProperty("lon_val1")
    val longitudeInDecimal: Double?,
    @param:JsonProperty("vil_dong_nm")
    val buildingNumber: String?,
    val name: String,
    @param:JsonProperty("ename")
    val englishName: String? = null,
    @param:JsonProperty("con_type")
    val contentType: String,
    @param:JsonProperty("fac_type")
    val facType: String,
)

@Component
class SnuMapClient(
    @Value("\${snutt.snumap.base-url:https://map.snu.ac.kr}") baseUrl: String,
    restClientBuilder: RestClient.Builder,
    private val jsonMapper: JsonMapper,
) {
    private val restClient = restClientBuilder.baseUrl(baseUrl).build()

    fun search(buildingNumber: String): SnuMapSearchResult {
        val body =
            restClient
                .get()
                .uri {
                    it
                        .path("/api/search.action")
                        .query("lang_type=KOR")
                        .queryParam("search_word", buildingNumber)
                        .build()
                }.retrieve()
                .body(String::class.java)
                ?: throw IllegalStateException("SNU 지도 검색 실패: $buildingNumber")
        return jsonMapper.readValue(body, SnuMapSearchResult::class.java)
    }
}

@Service
class LectureBuildingSync(
    private val snuMapClient: SnuMapClient,
    private val lectureBuildingRepository: LectureBuildingRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sync(places: List<String>) {
        val buildingNumbers =
            places
                .flatMap { PlaceInfo.getValuesOf(it) }
                .filter { it.campus == Campus.GWANAK }
                .map { it.buildingNumber }
                .distinct()
        if (buildingNumbers.isEmpty()) return
        val existing = lectureBuildingRepository.findByBuildingNumberIn(buildingNumbers).associateBy { it.buildingNumber }
        val failed = mutableListOf<String>()
        buildingNumbers.forEach { buildingNumber ->
            runCatching { update(buildingNumber, existing[buildingNumber]) }
                .onFailure { failed += buildingNumber }
        }
        if (failed.isNotEmpty()) log.warn("건물 정보를 갱신하지 못했다: {}", failed)
    }

    private fun update(
        buildingNumber: String,
        current: LectureBuilding?,
    ) {
        val item = snuMapClient.search(buildingNumber).mostProbableItem(buildingNumber) ?: return
        val fetched =
            LectureBuilding(
                buildingNumber = buildingNumber,
                buildingNameKor = item.name,
                buildingNameEng = item.englishName.orEmpty(),
                campus = Campus.GWANAK,
                locationInDms = coordinate(item.latitudeInDms, item.longitudeInDms) ?: current?.locationInDms,
                locationInDecimal = coordinate(item.latitudeInDecimal, item.longitudeInDecimal) ?: current?.locationInDecimal,
            )
        when {
            current == null -> lectureBuildingRepository.save(fetched)
            !current.sameAs(fetched) -> lectureBuildingRepository.save(current.apply { copyFrom(fetched) })
        }
    }

    private fun LectureBuilding.sameAs(other: LectureBuilding): Boolean =
        buildingNameKor == other.buildingNameKor &&
            buildingNameEng == other.buildingNameEng &&
            locationInDms == other.locationInDms &&
            locationInDecimal == other.locationInDecimal

    private fun LectureBuilding.copyFrom(other: LectureBuilding) {
        buildingNameKor = other.buildingNameKor
        buildingNameEng = other.buildingNameEng
        locationInDms = other.locationInDms
        locationInDecimal = other.locationInDecimal
    }

    private fun coordinate(
        latitude: Double?,
        longitude: Double?,
    ): GeoCoordinate? = if (latitude != null && longitude != null) GeoCoordinate(latitude, longitude) else null

    private fun SnuMapSearchResult.mostProbableItem(buildingNumber: String): SnuMapSearchItem? =
        searchList
            .filter { it.contentType == "F" && it.facType == "OTHER" && it.buildingNumber == buildingNumber }
            .minByOrNull { it.name.length }
}
