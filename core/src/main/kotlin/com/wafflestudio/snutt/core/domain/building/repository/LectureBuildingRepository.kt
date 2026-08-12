package com.wafflestudio.snutt.core.domain.building.repository

import com.wafflestudio.snutt.core.domain.building.model.LectureBuilding
import org.springframework.data.jpa.repository.JpaRepository

interface LectureBuildingRepository : JpaRepository<LectureBuilding, Long> {
    fun findByBuildingNumberIn(buildingNumbers: List<String>): List<LectureBuilding>
}
