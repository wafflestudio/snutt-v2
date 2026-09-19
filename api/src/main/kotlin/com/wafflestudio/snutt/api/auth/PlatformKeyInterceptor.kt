package com.wafflestudio.snutt.api.auth

import com.wafflestudio.snutt.core.common.client.PlatformKeys
import com.wafflestudio.snutt.core.common.client.clientInfoOf
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class PlatformKeyInterceptor(
    private val platformKeys: PlatformKeys,
) : HandlerInterceptor {
    companion object {
        const val PLATFORM_HEADER = "x-client-platform"
        const val KEY_HEADER = "x-client-key"
        const val CLIENT_INFO_ATTRIBUTE = "clientInfo"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val platform = request.getHeader(PLATFORM_HEADER)
        if (!platformKeys.matches(platform, request.getHeader(KEY_HEADER))) throw SnuttException(ErrorType.WRONG_API_KEY)
        request.setAttribute(CLIENT_INFO_ATTRIBUTE, clientInfoOf(request::getHeader, defaultOsType = platform))
        return true
    }
}
