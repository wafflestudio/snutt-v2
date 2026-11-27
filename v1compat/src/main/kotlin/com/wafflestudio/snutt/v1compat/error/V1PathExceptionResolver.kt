package com.wafflestudio.snutt.v1compat.error

import com.wafflestudio.snutt.core.common.error.SnuttException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.web.ErrorResponse
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import tools.jackson.databind.json.JsonMapper

/**
 * v1 경로에서 v1compat 컨트롤러가 아닌 곳에서 난 예외(매핑 없는 경로의 404, 지원하지 않는 메서드의 405,
 * 그런 경로에서 API key 인터셉터가 던진 예외)를 v1 에러 형식으로 응답한다.
 * v1compat 컨트롤러의 예외는 [V1CompatExceptionHandler]가 처리하므로 건드리지 않는다.
 * @ControllerAdvice는 handler가 없거나 컨트롤러가 아니면 basePackages로 고를 수 없어서 따로 둔다.
 */
class V1PathExceptionResolver(
    private val jsonMapper: JsonMapper,
    private val pathPrefixes: List<String>,
) : HandlerExceptionResolver {
    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (handler is HandlerMethod && handler.beanType.packageName.startsWith(V1COMPAT_PACKAGE)) return null
        if (!isV1Path(request) || response.isCommitted) return null
        val language = request.language
        val entity =
            if (ex is SnuttException) {
                ex.error.toV1ErrorResponse(language, ex.displayMessage(language))
            } else {
                unexpectedV1ErrorResponse(ex, language)
            }
        if (ex !is SnuttException && ex !is ErrorResponse) log.error("unhandled exception", ex)
        response.status = entity.statusCode.value()
        response.contentType = entity.headers.contentType.toString()
        response.characterEncoding = Charsets.UTF_8.name()
        jsonMapper.writeValue(response.outputStream, entity.body)
        return ModelAndView()
    }

    private fun isV1Path(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return pathPrefixes.any { path == it || path.startsWith("$it/") }
    }

    private companion object {
        const val V1COMPAT_PACKAGE = "com.wafflestudio.snutt.v1compat"
        val log = LoggerFactory.getLogger(V1PathExceptionResolver::class.java)
    }
}
