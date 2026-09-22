package com.wafflestudio.snutt.api.auth

import com.wafflestudio.snutt.core.common.client.CLIENT_INFO_ATTRIBUTE
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
        const val OS_TYPE_HEADER = "x-os-type"
        const val KEY_HEADER = "x-client-key"
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val osType =
            request.getHeader(OS_TYPE_HEADER)?.takeIf { platformKeys.matches(it, request.getHeader(KEY_HEADER)) }
                ?: throw SnuttException(ErrorType.WRONG_API_KEY)
        request.setAttribute(CLIENT_INFO_ATTRIBUTE, clientInfoOf(request::getHeader, osType))
        return true
    }
}
