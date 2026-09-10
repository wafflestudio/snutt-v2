package com.wafflestudio.snutt.core.domain.trace.repository

import com.wafflestudio.snutt.core.domain.trace.model.ApiTraceTarget
import org.springframework.data.jpa.repository.JpaRepository

interface ApiTraceTargetRepository : JpaRepository<ApiTraceTarget, Long> {
    fun findByUserId(userId: Long): ApiTraceTarget?

    fun deleteByUserId(userId: Long)
}
