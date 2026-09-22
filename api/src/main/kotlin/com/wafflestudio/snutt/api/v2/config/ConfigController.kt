package com.wafflestudio.snutt.api.v2.config

import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.CurrentClient
import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.domain.clientconfig.service.ClientConfigService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

@RestController
@Public
@RequestMapping("/v2/configs")
class ConfigController(
    private val configService: ClientConfigService,
) {
    @GetMapping("")
    fun getConfigs(
        @CurrentClient clientInfo: ClientInfo,
    ): Map<String, JsonNode> {
        val osType = OsType.from(clientInfo.osType) ?: return emptyMap()
        val appVersion = clientInfo.appVersion ?: return emptyMap()
        return configService
            .getConfigs(osType, appVersion)
            .associate { it.name to it.value }
    }
}
