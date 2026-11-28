package com.wafflestudio.snutt.core.domain.campusmap.repository

import com.wafflestudio.snutt.core.domain.campusmap.model.MapState
import org.springframework.data.jpa.repository.JpaRepository

interface MapStateRepository : JpaRepository<MapState, Long>
