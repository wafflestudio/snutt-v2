package com.wafflestudio.snutt.core.common.oci

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

enum class OciAuthType {
    INSTANCE,
    CONFIG,
}

@Configuration
@Profile("dev | prod")
class OciConfig(
    @param:Value("\${snutt.oci.auth-type}") private val authType: OciAuthType,
    @param:Value("\${snutt.oci.config-profile}") private val configProfile: String,
) {
    @Bean
    fun ociAuthProvider(): BasicAuthenticationDetailsProvider =
        when (authType) {
            OciAuthType.INSTANCE -> InstancePrincipalsAuthenticationDetailsProvider.builder().build()
            OciAuthType.CONFIG -> ConfigFileAuthenticationDetailsProvider(configProfile)
        }
}
