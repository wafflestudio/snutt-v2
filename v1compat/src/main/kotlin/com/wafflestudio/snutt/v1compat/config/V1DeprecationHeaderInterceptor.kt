package com.wafflestudio.snutt.v1compat.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class V1DeprecationHeaderInterceptor(
    @param:Value("\${snutt.v1-sunset:2027-12-31}") private val sunsetDate: String,
    @param:Value("\${snutt.v2-base-url:https://snutt.wafflestudio.com}") private val v2BaseUrl: String,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        response.setHeader("Deprecation", "true")
        response.setHeader("Sunset", sunsetDate)
        response.setHeader("Link", "<$v2BaseUrl/v2>; rel=\"successor-version\"")
        return true
    }
}
