package com.wafflestudio.snutt.parity.compare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 허용 차이·알려진 버그 목록은 parityTest(컨테이너 필요)에서만 읽힌다. 형식이 깨진 채 머지되지 않도록 PR CI에서 읽어 본다.
 */
class AllowedDiffsFileTest {
    @Test
    fun `허용 차이와 알려진 버그 목록을 읽을 수 있고 모든 항목에 이유가 있다`() {
        val allowed = AllowedDiffs.load()
        val known = AllowedDiffs.load(AllowedDiffs.KNOWN_FAILURES)

        assertTrue(allowed.entries.isNotEmpty())
        assertTrue(known.entries.all { !it.ref.isNullOrBlank() }) { "알려진 버그에는 버그 번호(ref)가 필요하다" }
        val overlap = allowed.entries.map { it.endpoint to it.path }.toSet() intersect known.entries.map { it.endpoint to it.path }.toSet()
        assertEquals(emptySet<Pair<String, String>>(), overlap) { "같은 차이를 허용과 알려진 버그에 동시에 두지 않는다" }
    }

    @Test
    fun `경로 glob은 중첩 경로와 선택지와 인덱스를 구별한다`() {
        val deep = AllowedDiff.toRegex("$.**.{remark,category}")
        assertTrue(deep.matches("$.remark"))
        assertTrue(deep.matches("$[0].category"))
        assertTrue(deep.matches("$.lecture_list[3].remark"))
        assertFalse(deep.matches("$.lecture_list[3].course_title"))

        val indexed = AllowedDiff.toRegex("$.lecture_list[*].color")
        assertTrue(indexed.matches("$.lecture_list[12].color"))
        assertFalse(indexed.matches("$.lecture_list[0].color.bg"))
        assertFalse(indexed.matches("$.color"))
    }

    @Test
    fun `엔드포인트 glob에서 경로 자리 표시는 글자 그대로다`() {
        assertTrue(AllowedDiff.endpointGlob("GET /v1/tables/{id}").matches("GET /v1/tables/{id}"))
        assertFalse(AllowedDiff.endpointGlob("GET /v1/tables/{id}").matches("GET /v1/tables/id"))
        assertTrue(AllowedDiff.endpointGlob("GET /v1/themes/{best,friends}*").matches("GET /v1/themes/friends"))
        assertTrue(AllowedDiff.endpointGlob("* /ev-service/v1/**").matches("POST /ev-service/v1/evaluations/{id}/report"))
    }
}
