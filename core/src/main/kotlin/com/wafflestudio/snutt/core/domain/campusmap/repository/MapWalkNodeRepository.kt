package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapWalkNode
import org.springframework.data.jpa.repository.JpaRepository

interface MapWalkNodeRepository : JpaRepository<MapWalkNode, Long>
