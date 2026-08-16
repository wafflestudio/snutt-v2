package com.wafflestudio.snutt.core.domain.clientconfig.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import com.wafflestudio.snutt.core.domain.clientconfig.repository.ClientConfigRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ClientConfigWriteRequest(
    val value: String,
    val minIosVersion: String? = null,
    val maxIosVersion: String? = null,
    val minAndroidVersion: String? = null,
    val maxAndroidVersion: String? = null,
)

@Service
class ClientConfigService(
    private val clientConfigRepository: ClientConfigRepository,
) {
    fun getConfigs(
        osType: String,
        appVersion: String,
    ): List<ClientConfig> =
        clientConfigRepository
            .findAll()
            .filter { it.isAdaptable(osType, appVersion) }
            .groupBy { it.name }
            .map { (_, configs) -> configs.maxBy { it.createdAt ?: java.time.Instant.EPOCH } }

    fun getConfigsByName(name: String): List<ClientConfig> = clientConfigRepository.findByNameOrderByCreatedAtDesc(name)

    @Transactional
    fun postConfig(
        name: String,
        request: ClientConfigWriteRequest,
    ): ClientConfig =
        clientConfigRepository.save(
            ClientConfig(
                name = name,
                value = request.value,
                minIosVersion = request.minIosVersion,
                maxIosVersion = request.maxIosVersion,
                minAndroidVersion = request.minAndroidVersion,
                maxAndroidVersion = request.maxAndroidVersion,
            ),
        )

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
        config.minIosVersion = request.minIosVersion
        config.maxIosVersion = request.maxIosVersion
        config.minAndroidVersion = request.minAndroidVersion
        config.maxAndroidVersion = request.maxAndroidVersion
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
    }

    @Transactional
    fun patchConfig(
        name: String,
        configExternalId: String,
        request: ClientConfigWriteRequest,
    ): ClientConfig = patchConfig(name, resolveConfigIdByExternalId(name, configExternalId), request)

    @Transactional
    fun deleteConfig(
        name: String,
        configExternalId: String,
    ) {
        deleteConfig(name, resolveConfigIdByExternalId(name, configExternalId))
    }

    private fun resolveConfigIdByExternalId(
        name: String,
        configExternalId: String,
    ): Long =
        clientConfigRepository
            .findByExternalId(configExternalId)
            ?.takeIf { it.name == name }
            ?.id
            ?: throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
}
