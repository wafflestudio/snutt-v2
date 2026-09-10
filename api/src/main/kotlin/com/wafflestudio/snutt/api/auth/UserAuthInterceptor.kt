package com.wafflestudio.snutt.api.auth

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.auth.service.AccessTokenService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * access token 인증은 상태를 조회하지 않는다. 서명과 만료만 검증하고 payload 의 식별자를 그대로 넘긴다.
 * 따라서 일반 endpoint 는 로그아웃/탈퇴가 access token 을 즉시 무효화하지 못하며, 최대 access token TTL 만큼 지연된다.
 * [AdminOnly] 와 [EmailVerifiedRequired] 는 active 계정만 통과시키고, 그 외 요청은 user 를 읽지 않는다.
 */
@Component
class UserAuthInterceptor(
    private val accessTokenService: AccessTokenService,
    private val userService: UserService,
) : HandlerInterceptor {
    companion object {
        const val USER_ID_ATTRIBUTE = "userId"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true
        if (handler.has(Public::class.java)) return true

        val authorization =
            request.getHeader("Authorization") ?: throw SnuttException(ErrorType.NO_USER_TOKEN)
        val token =
            authorization.removePrefix("Bearer ").takeIf { it != authorization }
                ?: throw SnuttException(ErrorType.NO_USER_TOKEN)

        val payload = accessTokenService.verify(token)

        val isAdminOnly = handler.has(AdminOnly::class.java)
        val isEmailVerifiedRequired = handler.has(EmailVerifiedRequired::class.java)
        if (isAdminOnly || isEmailVerifiedRequired) {
            val user = userService.findActive(payload.userId) ?: throw SnuttException(ErrorType.WRONG_USER_TOKEN)
            if (isAdminOnly && !user.isAdmin) throw SnuttException(ErrorType.USER_NOT_ADMIN)
            if (isEmailVerifiedRequired && !user.isEmailVerified) {
                throw SnuttException(ErrorType.USER_EMAIL_IS_NOT_VERIFIED)
            }
        }

        request.setAttribute(USER_ID_ATTRIBUTE, payload.userId)
        return true
    }

    private fun HandlerMethod.has(annotation: Class<out Annotation>) =
        hasMethodAnnotation(annotation) || beanType.isAnnotationPresent(annotation)
}
