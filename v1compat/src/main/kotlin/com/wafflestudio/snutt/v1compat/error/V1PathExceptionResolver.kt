package com.wafflestudio.snutt.v1compat.error

import com.wafflestudio.snutt.core.common.error.SnuttException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.server.PathContainer
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.util.ServletRequestPathUtils
import org.springframework.web.util.UrlPathHelper
import org.springframework.web.util.pattern.PathPatternParser
import tools.jackson.databind.json.JsonMapper

/**
 * v1 경로에서 v1compat 컨트롤러가 아닌 곳에서 난 예외(매핑 없는 경로의 404, 지원하지 않는 메서드의 405,
 * 그런 경로에서 API key 인터셉터가 던진 예외)를 v1 에러 형식으로 응답한다.
 * v1compat 컨트롤러의 예외는 [V1CompatExceptionHandler]가 처리하므로 건드리지 않는다.
 * @ControllerAdvice는 handler가 없거나 컨트롤러가 아니면 basePackages로 고를 수 없어서 따로 둔다.
 * 경로 판정은 v1 인터셉터와 같은 패턴을 Spring이 파싱한 경로(디코딩, `;` 파라미터 제외)에 맞춰서, 인터셉터가 걸린
 * 요청이면 이 resolver도 반드시 걸리게 한다.
 */
class V1PathExceptionResolver(
    private val jsonMapper: JsonMapper,
    pathPatterns: List<String>,
) : HandlerExceptionResolver {
    private val patterns = pathPatterns.map { PathPatternParser.defaultInstance.parse(it) }

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (handler is HandlerMethod && handler.beanType.packageName.startsWith(V1COMPAT_PACKAGE)) return null
        if (!isV1Path(request) || response.isCommitted) return null
        val language = request.language
        val snuttException = ex as? SnuttException ?: ex.wrappedSnuttException()
        val entity =
            if (snuttException != null) {
                snuttException.error.toV1ErrorResponse(language, snuttException.displayMessage(language))
            } else {
                unexpectedV1ErrorResponse(ex, language)
            }
        if (snuttException == null && ex.isServerError()) log.error("unhandled exception", ex)
        // 실패 전에 일부 써진 바디가 버퍼에 남아 있으면 에러 바디와 섞이므로 비운다(커밋 전이라 가능하다).
        response.resetBuffer()
        response.status = entity.statusCode.value()
        response.contentType = entity.headers.contentType.toString()
        response.characterEncoding = Charsets.UTF_8.name()
        jsonMapper.writeValue(response.outputStream, entity.body)
        return ModelAndView()
    }

    private fun isV1Path(request: HttpServletRequest): Boolean {
        val path =
            if (ServletRequestPathUtils.hasParsedRequestPath(request)) {
                ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication()
            } else {
                PathContainer.parsePath(UrlPathHelper.defaultInstance.getPathWithinApplication(request))
            }
        return patterns.any { it.matches(path) }
    }

    private companion object {
        const val V1COMPAT_PACKAGE = "com.wafflestudio.snutt.v1compat"
        val log = LoggerFactory.getLogger(V1PathExceptionResolver::class.java)
    }
}
