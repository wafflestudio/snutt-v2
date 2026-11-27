package com.wafflestudio.snutt.core.domain.feedback.service

import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.UpstreamException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Service
class FeedbackService(
    @param:Value("\${snutt.github.token}") private val token: String,
    @param:Value("\${snutt.github.repo-owner}") private val repoOwner: String,
    @param:Value("\${snutt.github.repo-name}") private val repoName: String,
    @param:Value("\${snutt.github.base-url}") baseUrl: String,
    restClientBuilder: RestClient.Builder,
    httpClientSettings: HttpClientSettings,
    private val clock: Clock,
) {
    // 이슈 생성은 멱등이 아니다. Apache HttpClient 5 기본 재시도(429·503에 1회, POST 포함)를 끄지 않으면 이슈가 중복 생성될 수 있다.
    // 타임아웃 등 나머지 설정(spring.http.clients.*)은 그대로 쓴다.
    private val restClient =
        restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(
                ClientHttpRequestFactoryBuilder
                    .httpComponents()
                    .withHttpClientCustomizer { it.disableAutomaticRetries() }
                    .build(httpClientSettings),
            ).build()

    fun postFeedback(
        email: String,
        message: String,
        osType: OsType,
        osVersion: String?,
        appVersion: String,
        deviceModel: String,
    ) {
        val platform = osVersion?.let { "${osType.value} ($osVersion)" } ?: osType.value
        val currentSeoulTime =
            ZonedDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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
                        "title" to "[SNUTT] $appVersion ${osType.value} 피드백",
                        "body" to body,
                        "labels" to listOf(osType.value),
                    ),
                ).retrieve()
                .toBodilessEntity()
        } catch (e: RestClientException) {
            throw UpstreamException(ErrorType.FEEDBACK_UPSTREAM_UNAVAILABLE, "github", e)
        }
    }
}
