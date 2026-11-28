package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapBuilding
import org.springframework.data.jpa.repository.JpaRepository

interface MapBuildingRepository : JpaRepository<MapBuilding, Long>
