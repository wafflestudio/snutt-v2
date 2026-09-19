package com.wafflestudio.snutt.core.domain.clientconfig.service

import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import com.wafflestudio.snutt.core.domain.clientconfig.repository.ClientConfigRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

data class ClientConfigWriteRequest(
    val value: String,
    val osType: OsType,
    val minVersion: String? = null,
    val maxVersion: String? = null,
)

@Service
class ClientConfigService(
    private val clientConfigRepository: ClientConfigRepository,
) {
    @Volatile
    private var snapshot: List<ClientConfig> = emptyList()

    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        refresh()
    }

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MILLIS)
    fun refresh() {
        snapshot = clientConfigRepository.findAll()
    }

    fun getConfigs(
        osType: OsType,
        appVersion: String,
    ): List<ClientConfig> =
        snapshot
            .filter { it.isAdaptable(osType, appVersion) }
            .groupBy { it.name }
            .map { (_, configs) -> configs.maxWith(WINNER) }

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
            ).also { refreshAfterCommit() }

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
        refreshAfterCommit()
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
        refreshAfterCommit()
    }

    private fun refreshAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = refresh()
            },
        )
    }

    companion object {
        private const val REFRESH_INTERVAL_MILLIS = 60_000L
        private val WINNER = compareBy<ClientConfig>({ it.createdAt ?: Instant.EPOCH }, { it.id ?: 0L })
    }
}
