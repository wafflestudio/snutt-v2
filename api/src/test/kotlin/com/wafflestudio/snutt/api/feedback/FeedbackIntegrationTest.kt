package com.wafflestudio.snutt.api.feedback

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.GITHUB_ISSUES
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.fixture
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.json
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.slow
import com.wafflestudio.snutt.api.testutil.SocialProviderStubs.Companion.status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import java.time.LocalDateTime

/** 피드백은 GitHub Issues API로 이슈를 만든다. GitHub가 어떤 이유로든 실패하면 502 feedback-upstream-unavailable이다. */
class FeedbackIntegrationTest : AbstractApiIntegrationTest() {
    private val stubs get() = SocialProviderStubs(wireMock)

    private fun postFeedback(): ResponseEntity<String> =
        client()
            .post()
            .uri("/v2/feedback")
            .headers { it.setBearerAuth(register("feedbackuser")) }
            .header("x-app-version", "3.9.0")
            .header("x-os-version", "18.1")
            .header("x-device-model", "iPhone16,1")
            .body("""{"email":"feedback@snu.ac.kr","message":"시간표가 안 보여요"}""")
            .retrieve()
            .toEntity(String::class.java)

    @Test
    fun `피드백을 GitHub 이슈로 올린다`() {
        clock.setKst(LocalDateTime.of(2026, 11, 27, 9, 5, 30))
        stubs.githubCreateIssue(json(fixture("github/issue-created.json"), status = 201))

        val response = postFeedback()

        assertEquals(200, response.statusCode.value(), "body=${response.body}")
        val expectedBody =
            listOf(
                "email: feedback@snu.ac.kr",
                "platform: ios (18.1)",
                "appVersion: 3.9.0",
                "deviceModel: iPhone16,1",
                "submittedAt (KST): 2026-11-27 09:05:30",
                "",
                "시간표가 안 보여요",
            ).joinToString("\n")
        wireMock.verify(
            postRequestedFor(urlPathEqualTo(GITHUB_ISSUES))
                .withHeader("Authorization", equalTo("Bearer test-github-token"))
                .withRequestBody(
                    equalToJson(
                        jsonMapper.writeValueAsString(
                            mapOf("title" to "[SNUTT] 3.9.0 ios 피드백", "body" to expectedBody, "labels" to listOf("ios")),
                        ),
                    ),
                ),
        )
    }

    @Test
    fun `GitHub 토큰이 만료돼 401이면 502로 알린다`() {
        stubs.githubCreateIssue(json(fixture("github/error-401.json"), status = 401))

        assertUpstreamUnavailable(postFeedback())
    }

    @Test
    fun `GitHub가 5xx면 502로 알린다`() {
        stubs.githubCreateIssue(status(500))

        assertUpstreamUnavailable(postFeedback())
    }

    // Apache HttpClient 5 기본 재시도는 429·503에 POST도 1회 다시 보낸다. 이슈 생성은 멱등이 아니라서 FeedbackService가 끈다.
    // 재시도했다면 두 번째 요청이 201을 받아 200이 나간다.
    @Test
    fun `GitHub가 503이면 이슈가 중복 생성되지 않도록 재시도하지 않고 502로 알린다`() {
        stubFailureThenCreated(503)

        assertUpstreamUnavailable(postFeedback())
    }

    @Test
    fun `GitHub가 429면 재시도하지 않고 502로 알린다`() {
        stubFailureThenCreated(429)

        assertUpstreamUnavailable(postFeedback())
    }

    private fun stubFailureThenCreated(code: Int) {
        wireMock.stubFor(
            post(urlPathEqualTo(GITHUB_ISSUES))
                .inScenario("github")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("retried")
                .willReturn(status(code)),
        )
        wireMock.stubFor(
            post(urlPathEqualTo(GITHUB_ISSUES))
                .inScenario("github")
                .whenScenarioStateIs("retried")
                .willReturn(json(fixture("github/issue-created.json"), status = 201)),
        )
    }

    @Test
    fun `GitHub 응답이 타임아웃되면 502로 알린다`() {
        stubs.githubCreateIssue(slow(json(fixture("github/issue-created.json"), status = 201)))

        assertUpstreamUnavailable(postFeedback())
    }

    private fun assertUpstreamUnavailable(response: ResponseEntity<String>) {
        assertEquals(502, response.statusCode.value(), "body=${response.body}")
        assertEquals("/problems/feedback-upstream-unavailable", body(response)["type"].asString())
        assertEquals(1, stubs.requestsTo(GITHUB_ISSUES), "재시도 없이 한 번만 호출한다")
    }
}
