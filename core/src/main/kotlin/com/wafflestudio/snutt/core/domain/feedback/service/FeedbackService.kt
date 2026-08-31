package com.wafflestudio.snutt.core.domain.feedback.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
        if (token.isBlank()) throw SnuttException(ErrorType.FEEDBACK_UPLOAD_FAILED)
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
    }
}
