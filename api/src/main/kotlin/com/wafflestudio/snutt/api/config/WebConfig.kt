package com.wafflestudio.snutt.api.config

import com.wafflestudio.snutt.api.auth.CurrentUserArgumentResolver
import com.wafflestudio.snutt.api.auth.PlatformKeyInterceptor
import com.wafflestudio.snutt.api.auth.UserAuthInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val platformKeyInterceptor: PlatformKeyInterceptor,
    private val userAuthInterceptor: UserAuthInterceptor,
    private val currentUserArgumentResolver: CurrentUserArgumentResolver,
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
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserArgumentResolver)
    }
}
