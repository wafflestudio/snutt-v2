package com.wafflestudio.snutt.api.v2.staticpage

import com.wafflestudio.snutt.api.auth.Public
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@Public
@RestController
class StaticPageController(
    private val resourceLoader: ResourceLoader,
) {
    companion object {
        private const val RESOURCE_PATH = "classpath:views"
        private val staticResponse =
            ResponseEntity
                .ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                .header("Content-Type", "text/html; charset=utf-8")
    }

    @GetMapping("/v2/static/member", produces = ["text/html; charset=utf-8"])
    fun member(): ResponseEntity<Resource> = serve("member.html")

    @GetMapping("/v2/static/privacy-policy", produces = ["text/html; charset=utf-8"])
    fun privacyPolicy(): ResponseEntity<Resource> = serve("privacy_policy.html")

    @GetMapping("/v2/static/terms-of-service", produces = ["text/html; charset=utf-8"])
    fun termsOfService(): ResponseEntity<Resource> = serve("terms_of_service.html")

    // 루트 경로는 앱스토어 심사 링크 등 외부 참조 호환을 위해 영구 리다이렉트로 유지한다
    @GetMapping("/member")
    fun memberLegacy(): ResponseEntity<Void> = permanentRedirectTo("/v2/static/member")

    @GetMapping("/privacy_policy")
    fun privacyPolicyLegacy(): ResponseEntity<Void> = permanentRedirectTo("/v2/static/privacy-policy")

    @GetMapping("/terms_of_service")
    fun termsOfServiceLegacy(): ResponseEntity<Void> = permanentRedirectTo("/v2/static/terms-of-service")

    private fun serve(name: String): ResponseEntity<Resource> = staticResponse.body(resourceLoader.getResource("$RESOURCE_PATH/$name"))

    private fun permanentRedirectTo(location: String): ResponseEntity<Void> =
        ResponseEntity
            .status(HttpStatus.MOVED_PERMANENTLY)
            .header(HttpHeaders.LOCATION, location)
            .build()
}
