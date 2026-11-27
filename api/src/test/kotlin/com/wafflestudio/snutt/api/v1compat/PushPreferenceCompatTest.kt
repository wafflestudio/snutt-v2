package com.wafflestudio.snutt.api.v1compat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * 푸시 설정 화면. 구앱(Android·iOS)은 `GET /v1/push/preferences`의 목록으로 토글을 그린다.
 * v1(snutt/core/.../PushPreferenceServiceImpl.getPushPreferenceDto)은 저장 이력이 없으면 NORMAL을 뺀 기본값 3개(모두 켜짐)를 줬다.
 * v2는 저장된 행만 돌려주고, 행이 없으면 켜진 것으로 보고 발송한다(PushService.findDisabledUserIds).
 */
class PushPreferenceCompatTest : AbstractV1CompatTest() {
    private fun JsonNode.asPreferenceMap(): Map<String, Boolean> =
        this["pushPreferences"].items().associate { it["type"].asString() to it["isEnabled"].asBoolean() }

    private fun JsonNode.types(): List<String> = this["pushPreferences"].items().map { it["type"].asString() }

    @Test
    fun `저장 이력이 없는 사용자에게 구앱은 v1 기본값 3개를 받는다`() {
        val (token, _) = v1Register("pushdefault")

        val preferences = body(v1Get("/v1/push/preferences", token))

        assertEquals(listOf("LECTURE_UPDATE", "VACANCY_NOTIFICATION", "DIARY"), preferences.types())
        assertEquals(setOf(true), preferences.asPreferenceMap().values.toSet())
    }

    @Test
    fun `신앱이 일부만 저장해도 구앱은 나머지를 기본값으로 채운 목록을 받고 v2 응답은 그대로다`() {
        val (legacyToken, _) = v1Register("pushpartial")
        val v2Token = v2Login("pushpartial")

        post("/v2/users/me/push-preferences", """{"pushPreferences":[{"type":"LECTURE_UPDATE","isEnabled":false}]}""", v2Token)

        val legacy = body(v1Get("/v1/push/preferences", legacyToken))
        assertEquals(listOf("LECTURE_UPDATE", "VACANCY_NOTIFICATION", "DIARY"), legacy.types())
        assertEquals(mapOf("LECTURE_UPDATE" to false, "VACANCY_NOTIFICATION" to true, "DIARY" to true), legacy.asPreferenceMap())
        assertEquals(mapOf("LECTURE_UPDATE" to false), body(get("/v2/users/me/push-preferences", v2Token)).asPreferenceMap())
    }

    @Test
    fun `구앱이 저장한 설정은 구앱과 신앱 모두에서 같은 값으로 보인다`() {
        val (legacyToken, _) = v1Register("pushsaved")
        val v2Token = v2Login("pushsaved")

        val saved =
            v1Post(
                "/v1/push/preferences",
                """{"pushPreferences":[{"type":"NORMAL","isEnabled":true},{"type":"LECTURE_UPDATE","isEnabled":true},""" +
                    """{"type":"VACANCY_NOTIFICATION","isEnabled":false},{"type":"DIARY","isEnabled":true}]}""",
                legacyToken,
            )
        assertEquals(200, saved.statusCode.value(), "body=${saved.body}")

        val expected = mapOf("NORMAL" to true, "LECTURE_UPDATE" to true, "VACANCY_NOTIFICATION" to false, "DIARY" to true)
        assertEquals(expected, body(v1Get("/v1/push/preferences", legacyToken)).asPreferenceMap())
        assertEquals(expected, body(get("/v2/users/me/push-preferences", v2Token)).asPreferenceMap())
    }
}
