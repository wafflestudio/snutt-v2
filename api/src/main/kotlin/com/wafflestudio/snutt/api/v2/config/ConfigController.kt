package com.wafflestudio.snutt.api.v2.config

import com.wafflestudio.snutt.api.auth.ClientInfo
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.domain.clientconfig.service.ClientConfigService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

// v1과 동일하게 value를 JSON 노드로 풀어서 반환한다
@RestController
@Public
@RequestMapping("/v2/configs")
class ConfigController(
    private val configService: ClientConfigService,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping("")
    fun getConfigs(
        @RequestAttribute clientInfo: ClientInfo,
    ): Map<String, JsonNode> {
        val osType = clientInfo.osType.lowercase()
        val appVersion = clientInfo.appVersion ?: return emptyMap()
        return configService
            .getConfigs(osType, appVersion)
            .associate { it.name to jsonMapper.readTree(it.value) }
    }
}
