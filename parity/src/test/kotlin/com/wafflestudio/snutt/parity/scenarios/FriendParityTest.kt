package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test

/** P1: 친구 목록·요청·수락·거절과 친구 시간표. */
@ParityTest
class FriendParityTest : AbstractParityTest() {
    @Test
    fun `이관된 친구 관계를 조회한다`() =
        parity("친구 조회") {
            get("/v1/friends?state=ACTIVE", `as` = "alice")
            get("/v1/friends?state=REQUESTED", `as` = "alice")
            get("/v1/friends?state=REQUESTING", `as` = "carol")
            get("/v1/friends/@{friend:alice-bob}/primary-table?year=2026&semester=3", `as` = "alice")
            get("/v1/friends/@{friend:alice-bob}/coursebooks", `as` = "alice")
            get("/v1/friends/@{friend:dave-alice}/primary-table?year=2026&semester=3", `as` = "alice", label = "대표 시간표가 중복이던 친구")
            get("/v1/friends/@{friend:alice-bob}/primary-table?year=2026&semester=1", `as` = "alice", label = "그 학기 시간표 없음")
        }

    @Test
    fun `친구 요청을 보내고 수락하고 이름을 바꾸고 끊는다`() =
        parity("친구 요청 흐름") {
            post("/v1/friends", """{"nickname":"캐롤#7777"}""", `as` = "bob")
            get("/v1/friends?state=REQUESTED", `as` = "carol").capture("friend:bob-carol", "/content/0/id")
            get("/v1/friends?state=REQUESTING", `as` = "bob")
            post("/v1/friends/@{friend:bob-carol}/accept", `as` = "carol")
            patch("/v1/friends/@{friend:bob-carol}/display-name", """{"displayName":"밥"}""", `as` = "carol")
            get("/v1/friends?state=ACTIVE", `as` = "carol")
            get("/v1/friends/@{friend:bob-carol}/primary-table?year=2026&semester=3", `as` = "carol")
            delete("/v1/friends/@{friend:bob-carol}", `as` = "bob")
            get("/v1/friends?state=ACTIVE", `as` = "carol", label = "끊은 뒤 목록")
            post("/v1/friends/@{friend:carol-alice}/decline", `as` = "alice")
            post("/v1/friends", """{"nickname":"없는사람#9999"}""", `as` = "bob", label = "없는 닉네임")
            post("/v1/friends", """{"nickname":"밥#0042"}""", `as` = "bob", label = "나에게 요청")
            post("/v1/friends", """{"nickname":"앨리스#1234"}""", `as` = "bob", label = "이미 친구")
        }
}
