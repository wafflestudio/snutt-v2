package com.wafflestudio.snutt.core.domain.feedback.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.UpstreamException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Service
class FeedbackService(
    @param:Value("\${snutt.github.token:}") private val token: String,
    @param:Value("\${snutt.github.repo-owner:wafflestudio}") private val repoOwner: String,
    @param:Value("\${snutt.github.repo-name:snutt-feedbacks}") private val repoName: String,
) {
    private val restClient = RestClient.builder().baseUrl("https://api.github.com").build()

    fun postFeedback(
        email: String,
        message: String,
        osType: String,
        osVersion: String?,
        appVersion: String,
        deviceModel: String,
    ) {
        check(token.isNotBlank()) { "깃허브 토큰이 설정되지 않았습니다" }
        val platform = osVersion?.let { "$osType ($osVersion)" } ?: osType
        val currentSeoulTime =
            ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val body =
            """
            |email: $email
            |platform: $platform
            |appVersion: $appVersion
            |deviceModel: $deviceModel
            |submittedAt (KST): $currentSeoulTime
            |
            |$message
            """.trimMargin()
        try {
            restClient
                .post()
                .uri("/repos/{owner}/{repo}/issues", repoOwner, repoName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "title" to "[SNUTT] $appVersion $osType 피드백",
                        "body" to body,
                        "labels" to listOf(osType.lowercase()),
                    ),
                ).retrieve()
                .toBodilessEntity()
        } catch (e: RestClientException) {
            throw UpstreamException(ErrorType.FEEDBACK_UPSTREAM_UNAVAILABLE, "github", e)
        }
    }
}
