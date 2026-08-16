package com.wafflestudio.snutt.api.v2.building

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.GeoCoordinate
import com.wafflestudio.snutt.core.domain.building.model.LectureBuilding
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.building.service.LectureBuildingService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class BuildingResponse(
    val id: String,
    val buildingNumber: String,
    val buildingNameKor: String,
    val buildingNameEng: String,
    val campus: Campus,
    val location: GeoCoordinate?,
)

private fun LectureBuilding.toResponse() =
    BuildingResponse(
        id = externalId,
        buildingNumber = buildingNumber,
        buildingNameKor = buildingNameKor,
        buildingNameEng = buildingNameEng,
        campus = campus,
        location = locationInDecimal,
    )

@RestController
@RequestMapping("/v2/buildings")
class BuildingController(
    private val lectureBuildingService: LectureBuildingService,
) {
    @Public
    @GetMapping("")
    fun searchBuildings(
        @RequestParam places: String,
    ): List<BuildingResponse> {
        val placeQuery =
            places
                .split(",")
                .flatMap { PlaceInfo.getValuesOf(it) }
                .distinct()
        return lectureBuildingService.getLectureBuildings(placeQuery).map { it.toResponse() }
    }
}
