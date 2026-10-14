package com.wafflestudio.snutt.v1compat.snutt

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class V1CompatStaticPageController {
    @GetMapping("/member")
    fun member(): ResponseEntity<Void> = permanentRedirectTo("/v2/static/member")

    @GetMapping("/privacy_policy")
    fun privacyPolicy(): ResponseEntity<Void> = permanentRedirectTo("/v2/static/privacy-policy")

    @GetMapping("/terms_of_service")
    fun termsOfService(): ResponseEntity<Void> = permanentRedirectTo("/v2/static/terms-of-service")

    private fun permanentRedirectTo(location: String): ResponseEntity<Void> =
        ResponseEntity
            .status(HttpStatus.MOVED_PERMANENTLY)
            .header(HttpHeaders.LOCATION, location)
            .build()
}
