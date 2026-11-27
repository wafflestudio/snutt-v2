package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test

/** P1: 강의 검색과 태그. 이관 직후(수강편람 동기화 전)에는 결과 집합과 순서가 같아야 한다. */
@ParityTest
class SearchParityTest : AbstractParityTest() {
    @Test
    fun `강의를 검색한다`() =
        parity("강의 검색") {
            post("/v1/search_query", """{"year":2026,"semester":3,"title":"운영"}""", `as` = "alice", label = "제목")
            post("/v1/search_query", """{"year":2026,"semester":3,"title":""}""", `as` = "alice", label = "전체(기본 정렬)")
            post("/v1/search_query", """{"year":2026,"semester":3,"classification":["교양"]}""", `as` = "alice", label = "분류")
            post("/v1/search_query", """{"year":2026,"semester":3,"credit":[2]}""", `as` = "alice", label = "학점")
            post("/v1/search_query", """{"year":2026,"semester":3,"title":"","limit":2,"offset":2}""", `as` = "alice", label = "페이지")
            post("/v1/search_query", """{"year":2025,"semester":3,"title":"이산"}""", `as` = "alice", label = "중복 개설 학기")
        }

    @Test
    fun `태그 목록을 받는다`() =
        parity("태그 목록") {
            get("/v1/tags/2026/3", `as` = "alice")
            get("/v1/tags/2026/3/update_time", `as` = "alice")
            get("/v1/tags/2030/1", `as` = "alice", label = "없는 학기")
        }
}
