package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapStructure
import org.springframework.data.jpa.repository.JpaRepository

interface MapStructureRepository : JpaRepository<MapStructure, Long>
