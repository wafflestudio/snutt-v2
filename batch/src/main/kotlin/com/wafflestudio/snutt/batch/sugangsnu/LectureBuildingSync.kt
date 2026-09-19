package com.wafflestudio.snutt.batch.sugangsnu

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.json.Json
import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.LectureBuilding
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.building.repository.LectureBuildingRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

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
        return Json.mapper.readValue(body, SnuMapSearchResult::class.java)
    }
}

@Service
class LectureBuildingSync(
    private val snuMapClient: SnuMapClient,
    private val lectureBuildingRepository: LectureBuildingRepository,
) {
    fun sync(places: List<String>) {
        val buildingNumbers =
            places
                .flatMap { PlaceInfo.getValuesOf(it) }
                .filter { it.campus == Campus.GWANAK }
                .map { it.buildingNumber }
                .distinct()
        if (buildingNumbers.isEmpty()) return
        val existing = lectureBuildingRepository.findByBuildingNumberIn(buildingNumbers).associateBy { it.buildingNumber }
        buildingNumbers.forEach { buildingNumber ->
            val item = snuMapClient.search(buildingNumber).mostProbableItem(buildingNumber) ?: return@forEach
            val fetched =
                LectureBuilding(
                    buildingNumber = buildingNumber,
                    buildingNameKor = item.name,
                    buildingNameEng = item.englishName.orEmpty(),
                    campus = Campus.GWANAK,
                    locationInDms = GeoCoordinate(item.latitudeInDms, item.longitudeInDms),
                    locationInDecimal = GeoCoordinate(item.latitudeInDecimal, item.longitudeInDecimal),
                )
            val current = existing[buildingNumber]
            when {
                current == null -> lectureBuildingRepository.save(fetched)
                !current.sameAs(fetched) -> lectureBuildingRepository.save(current.apply { copyFrom(fetched) })
            }
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

    private fun SnuMapSearchResult.mostProbableItem(buildingNumber: String): SnuMapSearchItem? =
        searchList
            .filter { it.contentType == "F" && it.facType == "OTHER" && it.buildingNumber == buildingNumber }
            .minByOrNull { it.name.length }
}
