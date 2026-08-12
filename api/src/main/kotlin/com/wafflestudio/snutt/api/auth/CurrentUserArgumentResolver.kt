package com.wafflestudio.snutt.api.auth

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

@Component
class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) ||
            parameter.hasParameterAnnotation(CurrentSessionId::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        if (parameter.hasParameterAnnotation(CurrentUser::class.java)) {
            return webRequest.getAttribute(UserAuthInterceptor.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
                as? User ?: throw SnuttException(ErrorType.NO_USER_TOKEN)
        }
        return webRequest.getAttribute(UserAuthInterceptor.SESSION_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
            as? String ?: throw SnuttException(ErrorType.NO_USER_TOKEN)
    }
}
