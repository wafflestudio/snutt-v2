package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapAccessPoint
import org.springframework.data.jpa.repository.JpaRepository

interface MapAccessPointRepository : JpaRepository<MapAccessPoint, Long>
