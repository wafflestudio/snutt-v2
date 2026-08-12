package com.wafflestudio.snutt.api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * HTTP QUERY 메서드(RFC 10008) 어댑터. Spring Framework는 아직 QUERY를 라우팅하지
 * 못하므로(spring-framework PR 미병합, 7.0.x 기준) 검색 전용 경로의 QUERY 요청을
 * 내부적으로 POST로 전환한다. QUERY는 safe+idempotent 읽기 메서드이므로 전환은
 * 서버 내부 표현일 뿐 외부 계약은 QUERY를 유지한다.
 *
 * Spring이 QUERY를 지원하게 되면 이 필터와 함께 @PostMapping → QUERY 매핑으로 교체한다.
 */
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
        // QUERY로 공개하는 엔드포인트. 새로 추가될 때 이 목록에 더한다
        private val QUERY_ENDPOINTS = listOf("/v2/lectures/search")
    }
}

private class QueryMethodRequestWrapper(
    request: HttpServletRequest,
) : HttpServletRequestWrapper(request) {
    override fun getMethod(): String = "POST"
}
