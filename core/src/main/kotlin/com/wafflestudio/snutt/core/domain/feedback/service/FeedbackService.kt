package com.wafflestudio.snutt.core.domain.feedback.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class FeedbackService(
    private val restClient: RestClient,
    @param:Value("\${snutt.github.token:}") private val token: String,
    @param:Value("\${snutt.github.repo-owner:wafflestudio}") private val repoOwner: String,
    @param:Value("\${snutt.github.repo-name:snutt-v2}") private val repoName: String,
) {
    fun postFeedback(
        email: String,
        message: String,
        osType: String,
        osVersion: String?,
        appVersion: String,
        deviceModel: String,
    ) {
        if (token.isBlank()) return
        val platform = osVersion?.let { "$osType ($osVersion)" } ?: osType
        val body =
            """
            |email: $email
            |platform: $platform
            |appVersion: $appVersion
            |deviceModel: $deviceModel
            |
            |$message
            """.trimMargin()
        restClient
            .post()
            .uri("/repos/{owner}/{repo}/issues", repoOwner, repoName)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("title" to "[SNUTT] $appVersion $osType 피드백", "body" to body))
            .retrieve()
            .toBodilessEntity()
    }
}
