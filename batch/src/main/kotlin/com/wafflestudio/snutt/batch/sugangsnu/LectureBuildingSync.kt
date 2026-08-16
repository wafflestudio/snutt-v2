package com.wafflestudio.snutt.batch.sugangsnu

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.LectureBuilding
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.building.repository.LectureBuildingRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

data class SnuMapSearchResult(
    @param:JsonProperty("search_list")
    val searchList: List<SnuMapSearchItem>,
)

data class SnuMapSearchItem(
    @param:JsonProperty("lat_val")
    val latitudeInDms: Double,
    @param:JsonProperty("lon_val")
    val longitudeInDms: Double,
    @param:JsonProperty("lat_val1")
    val latitudeInDecimal: Double = 0.0,
    @param:JsonProperty("lon_val1")
    val longitudeInDecimal: Double = 0.0,
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
) {
    private val restClient = RestClient.builder().baseUrl(baseUrl).build()

    companion object {
        private val jsonMapper = JsonMapper.builder().findAndAddModules().build()
    }

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
    @Transactional
    fun sync(places: List<String>) {
        val placeInfos =
            places
                .flatMap { PlaceInfo.getValuesOf(it) }
                .filter { it.campus == Campus.GWANAK }
                .distinct()
        if (placeInfos.isEmpty()) return
        val existing =
            lectureBuildingRepository
                .findByBuildingNumberIn(placeInfos.map { it.buildingNumber })
                .associateBy { it.buildingNumber }
        placeInfos.forEach { placeInfo ->
            val item = snuMapClient.search(placeInfo.buildingNumber).mostProbableItem(placeInfo.buildingNumber) ?: return@forEach
            val building =
                existing[placeInfo.buildingNumber]
                    ?: LectureBuilding(
                        buildingNumber = placeInfo.buildingNumber,
                        buildingNameKor = item.name,
                        campus = Campus.GWANAK,
                    )
            building.buildingNameKor = item.name
            building.buildingNameEng = item.englishName.orEmpty()
            building.locationInDms = GeoCoordinate(item.latitudeInDms, item.longitudeInDms)
            building.locationInDecimal = GeoCoordinate(item.latitudeInDecimal, item.longitudeInDecimal)
            lectureBuildingRepository.save(building)
        }
    }

    private fun SnuMapSearchResult.mostProbableItem(buildingNumber: String): SnuMapSearchItem? =
        searchList
            .filter { it.contentType == "F" && it.facType == "OTHER" && it.buildingNumber == buildingNumber }
            .minByOrNull { it.name.length }
}
