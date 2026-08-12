package com.wafflestudio.snutt.core.domain.clientconfig.repository

import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import org.springframework.data.jpa.repository.JpaRepository

interface ClientConfigRepository : JpaRepository<ClientConfig, Long> {
    fun findByNameOrderByCreatedAtDesc(name: String): List<ClientConfig>

    fun findByExternalId(externalId: String): ClientConfig?
}
