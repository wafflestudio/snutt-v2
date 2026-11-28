package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapWalkEdge
import org.springframework.data.jpa.repository.JpaRepository

interface MapWalkEdgeRepository : JpaRepository<MapWalkEdge, Long>
