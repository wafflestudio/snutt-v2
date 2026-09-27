package com.wafflestudio.snutt.api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

@Component
class RequestMdcFilter(
    private val jsonMapper: JsonMapper,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        MDC.put("method", request.method)
        MDC.put("path", request.requestURI)
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (response.status >= 500) {
                request.getAttribute(REQUEST_BODY_ATTRIBUTE)?.let { log.error("request body: {}", jsonMapper.writeValueAsString(it)) }
            }
            MDC.clear()
        }
    }

    companion object {
        const val REQUEST_BODY_ATTRIBUTE = "requestBody"
    }
}
