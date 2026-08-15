package com.wafflestudio.snutt.core.domain.building.service

import com.wafflestudio.snutt.core.domain.building.model.Campus
import com.wafflestudio.snutt.core.domain.building.model.LectureBuilding
import com.wafflestudio.snutt.core.domain.building.model.PlaceInfo
import com.wafflestudio.snutt.core.domain.building.repository.LectureBuildingRepository
import org.springframework.stereotype.Service

@Service
class LectureBuildingService(
    private val lectureBuildingRepository: LectureBuildingRepository,
) {
    fun getLectureBuildings(placeInfos: List<PlaceInfo>): List<LectureBuilding> {
        val buildingNumbers = placeInfos.filter { it.campus == Campus.GWANAK }.map { it.buildingNumber }
        return lectureBuildingRepository.findByBuildingNumberIn(buildingNumbers)
    }
}
