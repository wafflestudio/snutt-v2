package com.wafflestudio.snutt.core.common.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * HTTP로는 Tomcat이 헤더를 ISO-8859-1로 읽어 서로게이트가 들어오지 않으므로 해석 함수를 직접 부른다.
 * 기기 모델명처럼 이모지가 섞일 수 있는 값이 다른 경로로 들어와도 깨지지 않아야 한다.
 */
class ClientInfoOfTest {
    private fun deviceModelOf(value: String): String? {
        val headers = mapOf("x-device-model" to value)
        return clientInfoOf(headers::get, OsType.IOS).deviceModel
    }

    @Test
    fun `자른 끝에 서로게이트 쌍의 앞 절반만 남으면 버린다`() {
        // 63자 + 이모지(2 char) = 65 char. 64자로 자르면 이모지의 high surrogate만 남는다.
        val value = "m".repeat(63) + "😀"

        assertEquals("m".repeat(63), deviceModelOf(value))
    }

    @Test
    fun `서로게이트 쌍이 경계 안에 온전히 들어오거나 자르지 않으면 그대로 둔다`() {
        val fits = "m".repeat(62) + "😀" + "x"
        assertEquals("m".repeat(62) + "😀", deviceModelOf(fits))

        val exact = "m".repeat(62) + "😀"
        assertEquals(exact, deviceModelOf(exact))
    }
}
