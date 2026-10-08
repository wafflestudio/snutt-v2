package com.wafflestudio.snutt.api.misc

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.error.problemType
import com.wafflestudio.snutt.core.common.error.ErrorType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

class InvalidParameterIntegrationTest : AbstractApiIntegrationTest() {
    private fun assertInvalidParameter(response: ResponseEntity<String>) {
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(ErrorType.INVALID_PARAMETER.problemType.toString(), body(response)["type"].asString())
    }

    @Test
    fun `범위를 벗어난 학기 값은 400으로 응답한다`() {
        val token = register("paramuser")
        assertInvalidParameter(get("/v2/timetables/2026/9", token))
    }

    @Test
    fun `숫자가 아닌 학기 값은 400으로 응답한다`() {
        val token = register("paramuser")
        assertInvalidParameter(get("/v2/timetables/2026/abc", token))
        assertInvalidParameter(get("/v2/bookmarks?year=2026&semester=abc", token))
    }
}
