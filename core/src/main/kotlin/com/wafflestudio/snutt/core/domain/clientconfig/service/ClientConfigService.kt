package com.wafflestudio.snutt.core.domain.clientconfig.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import com.wafflestudio.snutt.core.domain.clientconfig.repository.ClientConfigRepository
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
    // os/버전 범위에 맞는 설정만, name 기준 최신 1건 (v1 동일)
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
        configExternalId: String,
        request: ClientConfigWriteRequest,
    ): ClientConfig {
        val config =
            clientConfigRepository.findByExternalId(configExternalId)
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
        configExternalId: String,
    ) {
        val config =
            clientConfigRepository.findByExternalId(configExternalId)
                ?: throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
        if (config.name != name) throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
        clientConfigRepository.delete(config)
    }
}
