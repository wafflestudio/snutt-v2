package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapEntrance
import org.springframework.data.jpa.repository.JpaRepository

interface MapEntranceRepository : JpaRepository<MapEntrance, Long>
