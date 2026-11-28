package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapTransitStop
import org.springframework.data.jpa.repository.JpaRepository

interface MapTransitStopRepository : JpaRepository<MapTransitStop, Long>
