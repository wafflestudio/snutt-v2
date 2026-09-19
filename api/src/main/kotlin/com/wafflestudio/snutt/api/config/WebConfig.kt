package com.wafflestudio.snutt.api.config

import com.wafflestudio.snutt.api.auth.CurrentUserArgumentResolver
import com.wafflestudio.snutt.api.auth.PlatformKeyInterceptor
import com.wafflestudio.snutt.api.auth.UserAuthInterceptor
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.format.FormatterRegistry
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
            .excludePathPatterns("/v2/static/member", "/v2/static/privacy-policy", "/v2/static/terms-of-service")
            .order(1)
        registry
            .addInterceptor(userAuthInterceptor)
            .addPathPatterns("/v2/**")
            .order(2)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserArgumentResolver)
    }

    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(SemesterConverter)
        registry.addConverter(DayOfWeekConverter)
    }

    private object SemesterConverter : Converter<String, Semester?> {
        override fun convert(source: String): Semester? = source.toIntOrNull()?.let(Semester::fromValue)
    }

    private object DayOfWeekConverter : Converter<String, DayOfWeek?> {
        override fun convert(source: String): DayOfWeek? = source.toIntOrNull()?.let(DayOfWeek::fromValue)
    }
}
