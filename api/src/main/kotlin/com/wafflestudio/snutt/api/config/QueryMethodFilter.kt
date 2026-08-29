package com.wafflestudio.snutt.api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class QueryMethodFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method.equals("QUERY", ignoreCase = true) && isQueryEndpoint(request.requestURI)) {
            filterChain.doFilter(QueryMethodRequestWrapper(request), response)
        } else {
            filterChain.doFilter(request, response)
        }
    }

    private fun isQueryEndpoint(uri: String): Boolean = QUERY_ENDPOINTS.any { uri.startsWith(it) }

    companion object {
        private val QUERY_ENDPOINTS = listOf("/v2/lectures/search")
    }
}

private class QueryMethodRequestWrapper(
    request: HttpServletRequest,
) : HttpServletRequestWrapper(request) {
    override fun getMethod(): String = "POST"
}
