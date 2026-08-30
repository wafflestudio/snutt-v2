package com.wafflestudio.snutt.api.trace

import com.wafflestudio.snutt.api.auth.PlatformKeyInterceptor
import com.wafflestudio.snutt.api.auth.UserAuthInterceptor
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1UserAuthInterceptor
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

@Component
class ApiTraceInterceptor(
    private val apiTraceTargetRegistry: ApiTraceTargetRegistry,
) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(API_TRACE_LOGGER)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val userId = request.userId() ?: return true
        if (!apiTraceTargetRegistry.isTarget(userId)) return true
        request.setAttribute(STARTED_AT_ATTRIBUTE, System.nanoTime())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val startedAt = request.getAttribute(STARTED_AT_ATTRIBUTE) as? Long ?: return
        val userId = request.userId() ?: return

        val clientInfo =
            request.getAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) as? ClientInfo
                ?: request.getAttribute(PlatformKeyInterceptor.CLIENT_INFO_ATTRIBUTE) as? ClientInfo
        val path =
            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
                ?: request.requestURI

        log.info(
            "api trace: {} {} -> {} userId={} appVersion={} durationMs={} query={} error={}",
            request.method,
            path,
            response.status,
            userId,
            clientInfo?.appVersion,
            (System.nanoTime() - startedAt) / 1_000_000,
            request.queryString,
            ex?.javaClass?.simpleName,
        )
    }

    private fun HttpServletRequest.userId(): Long? =
        getAttribute(UserAuthInterceptor.USER_ID_ATTRIBUTE) as? Long
            ?: (getAttribute(V1UserAuthInterceptor.USER_ATTRIBUTE) as? User)?.id

    companion object {
        const val API_TRACE_LOGGER = "snutt.api.trace"
        private const val STARTED_AT_ATTRIBUTE = "snutt.apiTrace.startedAt"
    }
}
