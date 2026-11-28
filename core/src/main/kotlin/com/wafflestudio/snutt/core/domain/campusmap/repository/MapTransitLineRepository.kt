package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapTransitLine
import org.springframework.data.jpa.repository.JpaRepository

interface MapTransitLineRepository : JpaRepository<MapTransitLine, Long>
