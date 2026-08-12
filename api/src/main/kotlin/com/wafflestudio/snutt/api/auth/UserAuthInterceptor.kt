package com.wafflestudio.snutt.api.auth

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.service.AccessTokenService
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

@Component
class UserAuthInterceptor(
    private val accessTokenService: AccessTokenService,
    private val userRepository: UserRepository,
) : HandlerInterceptor {
    companion object {
        const val USER_ATTRIBUTE = "user"
        const val SESSION_ATTRIBUTE = "sessionExternalId"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true
        val isPublic =
            handler.hasMethodAnnotation(Public::class.java) ||
                handler.beanType.isAnnotationPresent(Public::class.java)
        val isAdminOnly =
            handler.hasMethodAnnotation(AdminOnly::class.java) ||
                handler.beanType.isAnnotationPresent(AdminOnly::class.java)
        if (isPublic) return true

        val authorization =
            request.getHeader("Authorization") ?: throw SnuttException(ErrorType.NO_USER_TOKEN)
        val token =
            authorization.removePrefix("Bearer ").takeIf { it != authorization }
                ?: throw SnuttException(ErrorType.NO_USER_TOKEN)

        val payload = accessTokenService.verify(token)
        val user =
            userRepository.findByExternalIdAndActiveTrue(payload.userExternalId)
                ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)
        if (isAdminOnly && !user.isAdmin) throw SnuttException(ErrorType.USER_NOT_ADMIN)
        val isEmailVerifiedRequired =
            handler.hasMethodAnnotation(EmailVerifiedRequired::class.java) ||
                handler.beanType.isAnnotationPresent(EmailVerifiedRequired::class.java)
        if (isEmailVerifiedRequired && !user.isEmailVerified) throw SnuttException(ErrorType.USER_EMAIL_IS_NOT_VERIFIED)

        request.setAttribute(USER_ATTRIBUTE, user)
        request.setAttribute(SESSION_ATTRIBUTE, payload.sessionExternalId)
        return true
    }
}
