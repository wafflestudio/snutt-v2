package com.wafflestudio.snutt.core.common.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalIdGeneratorTest {
    @Test
    fun `32자리 대문자 base32를 생성한다`() {
        val id = ExternalIdGenerator.generate()
        assertEquals(32, id.length)
        assertTrue(id.matches(Regex("^[0-9A-Z]{32}$")))
    }

    @Test
    fun `연속 생성 시 중복이 없다`() {
        val ids = (1..10_000).map { ExternalIdGenerator.generate() }
        assertEquals(ids.size, ids.toSet().size)
    }
}
