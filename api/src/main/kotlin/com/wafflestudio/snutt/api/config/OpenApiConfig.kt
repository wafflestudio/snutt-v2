package com.wafflestudio.snutt.api.config

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.api.auth.Public
import com.wafflestudio.snutt.core.common.client.CurrentClient
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.config.V1CompatConfig
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod

@Configuration
class OpenApiConfig {
    init {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(
            CurrentUserId::class.java,
            CurrentClient::class.java,
            V1CurrentUser::class.java,
        )
    }

    @Bean
    fun v2OpenApi(): GroupedOpenApi =
        GroupedOpenApi
            .builder()
            .group("v2")
            .pathsToMatch("/v2/**")
            .addOpenApiCustomizer { it.components(components(OS_TYPE, CLIENT_KEY, BEARER)) }
            .addOperationCustomizer { operation, handler ->
                val requirement = SecurityRequirement().addList(OS_TYPE).addList(CLIENT_KEY)
                if (!handler.has(Public::class.java)) requirement.addList(BEARER)
                operation.addSecurityItem(requirement).withClientHeaders(handler)
            }.build()

    @Bean
    fun v1CompatOpenApi(): GroupedOpenApi =
        GroupedOpenApi
            .builder()
            .group("v1compat")
            .pathsToMatch(*V1CompatConfig.PATH_PATTERNS)
            .addOpenApiCustomizer { it.components(components(LEGACY_API_KEY, OS_TYPE, CLIENT_KEY, LEGACY_TOKEN)) }
            .addOperationCustomizer { operation, handler ->
                val requirements =
                    listOf(
                        SecurityRequirement().addList(LEGACY_API_KEY),
                        SecurityRequirement().addList(OS_TYPE).addList(CLIENT_KEY),
                    )
                if (!handler.has(V1Public::class.java)) requirements.forEach { it.addList(LEGACY_TOKEN) }
                operation.security(requirements).withClientHeaders(handler)
            }.build()

    private fun components(vararg names: String): Components =
        Components().securitySchemes(names.associateWith { SECURITY_SCHEMES.getValue(it) })

    private fun Operation.withClientHeaders(handler: HandlerMethod): Operation {
        if (handler.methodParameters.any { it.hasParameterAnnotation(CurrentClient::class.java) }) {
            CLIENT_HEADERS.forEach { addParametersItem(HeaderParameter().name(it).required(false)) }
        }
        return this
    }

    private fun HandlerMethod.has(annotation: Class<out Annotation>): Boolean =
        hasMethodAnnotation(annotation) || beanType.isAnnotationPresent(annotation)

    companion object {
        private const val OS_TYPE = "osType"
        private const val CLIENT_KEY = "clientKey"
        private const val BEARER = "bearer"
        private const val LEGACY_API_KEY = "legacyApiKey"
        private const val LEGACY_TOKEN = "legacyToken"

        private val SECURITY_SCHEMES =
            mapOf(
                OS_TYPE to headerScheme("x-os-type"),
                CLIENT_KEY to headerScheme("x-client-key"),
                BEARER to SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"),
                LEGACY_API_KEY to headerScheme("x-access-apikey"),
                LEGACY_TOKEN to headerScheme("x-access-token"),
            )

        private val CLIENT_HEADERS = listOf("x-app-version", "x-app-type", "x-os-version", "x-device-id", "x-device-model", "x-language")

        private fun headerScheme(name: String): SecurityScheme =
            SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name(name)
    }
}
