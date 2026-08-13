package com.wafflestudio.snutt.api.config

import com.wafflestudio.snutt.api.auth.CurrentUserArgumentResolver
import com.wafflestudio.snutt.api.auth.PlatformKeyInterceptor
import com.wafflestudio.snutt.api.auth.UserAuthInterceptor
import com.wafflestudio.snutt.api.v1compat.DeprecationHeaderInterceptor
import com.wafflestudio.snutt.api.v1compat.V1CompatApiKeyInterceptor
import com.wafflestudio.snutt.api.v1compat.V1CompatUserAuthInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val platformKeyInterceptor: PlatformKeyInterceptor,
    private val userAuthInterceptor: UserAuthInterceptor,
    private val currentUserArgumentResolver: CurrentUserArgumentResolver,
    private val v1CompatApiKeyInterceptor: V1CompatApiKeyInterceptor,
    private val v1CompatUserAuthInterceptor: V1CompatUserAuthInterceptor,
    private val deprecationHeaderInterceptor: DeprecationHeaderInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry
            .addInterceptor(platformKeyInterceptor)
            .addPathPatterns("/v2/**")
            .order(1)
        registry
            .addInterceptor(userAuthInterceptor)
            .addPathPatterns("/v2/**")
            .order(2)
        // v1 호환 경로. 경로는 /v1/** 하나뿐이라 인터셉터 등록과 라우팅이 같은 집합을 덮는다
        registry
            .addInterceptor(deprecationHeaderInterceptor)
            .addPathPatterns("/v1/**")
            .order(1)
        registry
            .addInterceptor(v1CompatApiKeyInterceptor)
            .addPathPatterns("/v1/**")
            .order(2)
        registry
            .addInterceptor(v1CompatUserAuthInterceptor)
            .addPathPatterns("/v1/**")
            .order(3)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserArgumentResolver)
    }
}
