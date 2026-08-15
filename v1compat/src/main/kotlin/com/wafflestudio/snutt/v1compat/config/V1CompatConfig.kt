package com.wafflestudio.snutt.v1compat.config

import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1UserAuthInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class V1CompatConfig(
    private val apiKeyInterceptor: V1ApiKeyInterceptor,
    private val userAuthInterceptor: V1UserAuthInterceptor,
    private val deprecationHeaderInterceptor: V1DeprecationHeaderInterceptor,
    private val currentUserArgumentResolver: V1CurrentUserArgumentResolver,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(deprecationHeaderInterceptor).addPathPatterns(*PATH_PATTERNS).order(1)
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns(*PATH_PATTERNS).order(2)
        registry.addInterceptor(userAuthInterceptor).addPathPatterns(*PATH_PATTERNS).order(3)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserArgumentResolver)
    }

    companion object {
        val PATH_PATTERNS = arrayOf("/v1/**", "/admin/**")
    }
}

@Component
class V1CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(V1CurrentUser::class.java) && parameter.parameterType == User::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? = webRequest.getAttribute(V1UserAuthInterceptor.USER_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST)
}
