package com.wafflestudio.snutt.core.domain.clientconfig.service

import com.wafflestudio.snutt.core.common.cache.RedisCache
import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.common.client.compareAppVersions
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.transaction.afterCommit
import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import com.wafflestudio.snutt.core.domain.clientconfig.repository.ClientConfigRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.time.Duration

data class ClientConfigWriteRequest(
    val value: JsonNode,
    val osType: OsType,
    val minVersion: String? = null,
    val maxVersion: String? = null,
)

private data class CachedClientConfig(
    val name: String,
    val osType: OsType,
    val minVersion: String?,
    val maxVersion: String?,
    val value: JsonNode,
) {
    fun isAdaptable(
        osType: OsType,
        appVersion: String,
    ): Boolean =
        this.osType == osType &&
            (minVersion == null || compareAppVersions(appVersion, minVersion) >= 0) &&
            (maxVersion == null || compareAppVersions(appVersion, maxVersion) <= 0)
}

@Service
class ClientConfigService(
    private val clientConfigRepository: ClientConfigRepository,
    private val redisCache: RedisCache,
) {
    fun getConfigs(
        osType: OsType,
        appVersion: String,
    ): Map<String, JsonNode> =
        redisCache
            .getOrPut(CACHE_KEY, CACHE_TTL, Array<CachedClientConfig>::class.java) {
                clientConfigRepository
                    .findAllByOrderByCreatedAtAscIdAsc()
                    .map { CachedClientConfig(it.name, it.osType, it.minVersion, it.maxVersion, it.value) }
                    .toTypedArray()
            }.filter { it.isAdaptable(osType, appVersion) }
            .associate { it.name to it.value }

    fun getConfigsByName(name: String): List<ClientConfig> = clientConfigRepository.findByNameOrderByCreatedAtDesc(name)

    @Transactional
    fun postConfig(
        name: String,
        request: ClientConfigWriteRequest,
    ): ClientConfig =
        clientConfigRepository
            .save(
                ClientConfig(
                    name = name,
                    osType = request.osType,
                    value = request.value,
                    minVersion = request.minVersion,
                    maxVersion = request.maxVersion,
                ),
            ).also { evictAfterCommit() }

    @Transactional
    fun patchConfig(
        name: String,
        configId: Long,
        request: ClientConfigWriteRequest,
    ): ClientConfig {
        val config =
            clientConfigRepository.findByIdOrNull(configId)
                ?: throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
        if (config.name != name) throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
        config.value = request.value
        config.osType = request.osType
        config.minVersion = request.minVersion
        config.maxVersion = request.maxVersion
        evictAfterCommit()
        return config
    }

    @Transactional
    fun deleteConfig(
        name: String,
        configId: Long,
    ) {
        val config =
            clientConfigRepository.findByIdOrNull(configId)
                ?: throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
        if (config.name != name) throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
        clientConfigRepository.delete(config)
        evictAfterCommit()
    }

    private fun evictAfterCommit() {
        afterCommit { redisCache.evict(CACHE_KEY) }
    }

    companion object {
        private const val CACHE_KEY = "client-configs"
        private val CACHE_TTL: Duration = Duration.ofMinutes(5)
    }
}
