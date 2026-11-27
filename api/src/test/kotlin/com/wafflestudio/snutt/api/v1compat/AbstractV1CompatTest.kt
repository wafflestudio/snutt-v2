package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.legacyApiKey
import com.wafflestudio.snutt.core.common.client.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

/**
 * 구버전 앱(v1compat 엔드포인트)과 신버전 앱(v2 엔드포인트)을 한 테스트에서 함께 부르는 시나리오 베이스.
 * 구앱 요청은 `x-access-apikey`(레거시 JWT)와 `x-access-token`(레거시 토큰)으로 인증한다.
 * 클래스 단위 어노테이션을 더하지 않으므로 api 컨텍스트를 그대로 공유한다.
 */
abstract class AbstractV1CompatTest : AbstractApiIntegrationTest() {
    protected fun v1Send(
        method: HttpMethod,
        uri: String,
        body: String? = null,
        legacyToken: String? = null,
        language: Language? = null,
        apiKey: String? = legacyApiKey("ios", "0"),
        headers: Map<String, String> = emptyMap(),
    ): ResponseEntity<String> {
        val builder =
            RestClient
                .builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }) { _, _ -> }
                .defaultHeader("Content-Type", "application/json")
        apiKey?.let { builder.defaultHeader("x-access-apikey", it) }
        language?.let { builder.defaultHeader("x-language", it.name.lowercase()) }
        val spec = builder.build().method(method).uri(uri)
        spec.headers { h ->
            legacyToken?.let { h.set("x-access-token", it) }
            headers.forEach { (key, value) -> h.set(key, value) }
        }
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    protected fun v1Get(
        uri: String,
        legacyToken: String? = null,
        language: Language? = null,
    ): ResponseEntity<String> = v1Send(HttpMethod.GET, uri, legacyToken = legacyToken, language = language)

    protected fun v1Post(
        uri: String,
        body: String? = null,
        legacyToken: String? = null,
        language: Language? = null,
    ): ResponseEntity<String> = v1Send(HttpMethod.POST, uri, body, legacyToken, language)

    protected fun v1Put(
        uri: String,
        body: String? = null,
        legacyToken: String? = null,
    ): ResponseEntity<String> = v1Send(HttpMethod.PUT, uri, body, legacyToken)

    protected fun v1Delete(
        uri: String,
        legacyToken: String? = null,
    ): ResponseEntity<String> = v1Send(HttpMethod.DELETE, uri, legacyToken = legacyToken)

    /** 구앱으로 가입하고 레거시 토큰과 사용자 id(문자열)를 돌려준다. */
    protected fun v1Register(
        localId: String,
        password: String = "password1",
        email: String = "$localId@snu.ac.kr",
    ): Pair<String, String> {
        val response = v1Post("/v1/auth/register_local", """{"id":"$localId","password":"$password","email":"$email"}""")
        assertEquals(200, response.statusCode.value(), "body=${response.body}")
        return body(response)["token"].asString() to body(response)["user_id"].asString()
    }

    /** 신앱으로 로그인해 v2 액세스 토큰을 돌려준다. */
    protected fun v2Login(
        localId: String,
        password: String = "password1",
    ): String {
        val response = post("/v2/auth/login", """{"localId":"$localId","password":"$password"}""")
        assertEquals(200, response.statusCode.value(), "body=${response.body}")
        return body(response)["accessToken"].asString()
    }

    /** v1 에러 바디(`{errcode, title, message, displayMessage}`)인지 확인하고 errcode를 돌려준다. */
    protected fun assertV1Error(
        response: ResponseEntity<String>,
        status: Int,
        errcode: Long,
    ) {
        assertEquals(status, response.statusCode.value(), "body=${response.body}")
        assertEquals("application/json", response.headers.contentType?.let { "${it.type}/${it.subtype}" }, "body=${response.body}")
        val error = body(response)
        assertEquals(errcode, error["errcode"].asLong(), "body=${response.body}")
        listOf("title", "message", "displayMessage").forEach { key ->
            assertEquals(true, error.has(key) && error[key].isString, "$key 누락: ${response.body}")
        }
    }
}
