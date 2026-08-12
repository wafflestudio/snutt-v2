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
    // v1은 관악 캠퍼스 건물만 조회한다 (연건/평창은 데이터가 없다)
    fun getLectureBuildings(placeInfos: List<PlaceInfo>): List<LectureBuilding> {
        val buildingNumbers = placeInfos.filter { it.campus == Campus.GWANAK }.map { it.buildingNumber }
        return lectureBuildingRepository.findByBuildingNumberIn(buildingNumbers)
    }
}
