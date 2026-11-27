package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test

/** P0·P1: 시간표 조회·CRUD, 강의 추가·겹침·강제 추가, 직접 만든 강의 수정. */
@ParityTest
class TimetableParityTest : AbstractParityTest() {
    @Test
    fun `이관된 시간표를 조회한다`() =
        parity("시간표 조회: 이관 데이터") {
            get("/v1/tables/@{tt:alice-main}", `as` = "alice", label = "강의 3개(직접 만든 강의 포함)")
            get("/v1/tables/@{tt:alice-plan-b}", `as` = "alice", label = "사용자 테마, 이름 바꾼 강의, 원격 강의")
            get("/v1/tables/@{tt:alice-spring}", `as` = "alice", label = "is_primary 없는 시간표")
            get("/v1/tables/2026/3", `as` = "alice", label = "학기별 목록")
            get("/v1/tables/@{tt:bob-main}", `as` = "bob", label = "범위 밖 팔레트 번호")
            get("/v1/tables/@{tt:carol-main}", `as` = "carol", label = "한쪽 색만 있는 직접 만든 강의")
            get("/v1/tables/@{tt:bob-main}", `as` = "alice", label = "남의 시간표")
        }

    @Test
    fun `시간표를 만들고 고치고 지운다`() =
        parity("시간표 CRUD") {
            post("/v1/tables", """{"year":2026,"semester":3,"title":"새 시간표"}""", `as` = "alice")
                .capture("tt:new", "/-1/_id")
            put("/v1/tables/@{tt:new}", """{"title":"이름 바꾼 시간표"}""", `as` = "alice")
            put("/v1/tables/@{tt:new}", """{"title":"이름 바꾼 시간표"}""", `as` = "alice", label = "같은 제목으로 저장")
            put("/v1/tables/@{tt:new}", """{"title":"나의 시간표"}""", `as` = "alice", label = "다른 시간표와 같은 제목")
            post("/v1/tables/@{tt:new}/copy", `as` = "alice")
            put("/v1/tables/@{tt:new}/theme", """{"theme":3}""", `as` = "alice", label = "기본 테마 바꾸기")
            put("/v1/tables/@{tt:new}/theme", """{"themeId":"@{theme:alice-custom}"}""", `as` = "alice", label = "사용자 테마로")
            post("/v1/tables/@{tt:new}/primary", `as` = "alice")
            get("/v1/tables/2026/3", `as` = "alice")
            delete("/v1/tables/@{tt:new}/primary", `as` = "alice")
            delete("/v1/tables/@{tt:new}", `as` = "alice")
            get("/v1/tables/@{tt:new}", `as` = "alice", label = "지운 시간표")
            post("/v1/tables", """{"year":2026,"semester":4,"title":"겨울"}""", `as` = "carol", label = "수강편람 없는 학기")
            post("/v1/tables", """{"year":2026,"semester":3,"title":"두 번째"}""", `as` = "carol").capture("tt:carol-second", "/-1/_id")
            delete("/v1/tables/@{tt:carol-main}", `as` = "carol", label = "대표 시간표 삭제")
            get("/v1/tables", `as` = "carol", label = "대표를 지운 뒤 목록")
            delete("/v1/tables/@{tt:carol-second}", `as` = "carol", label = "마지막 시간표 삭제")
        }

    @Test
    fun `강의를 추가하다 시간이 겹친다`() =
        parity("강의 추가: 겹침과 강제 추가") {
            post("/v1/tables/@{tt:alice-main}/lecture/@{lecture:arch}", "{}", `as` = "alice", label = "겹치는 강의")
            post("/v1/tables/@{tt:alice-main}/lecture/@{lecture:arch}", """{"is_forced":true}""", `as` = "alice", label = "강제 추가")
            post("/v1/tables/@{tt:alice-main}/lecture/@{lecture:arch}", "{}", `as` = "alice", label = "이미 있는 강의")
            post("/v1/tables/@{tt:alice-main}/lecture/@{lecture:online}", "{}", `as` = "alice", label = "시간 없는 강의")
            post("/v1/tables/@{tt:alice-main}/lecture/@{lecture:os-spring}", "{}", `as` = "alice", label = "다른 학기 강의")
            get("/v1/tables/@{tt:alice-main}", `as` = "alice")
        }

    @Test
    fun `강의를 추가하면 가장 덜 쓴 팔레트 색을 받는다`() =
        parity("강의 추가: 색 자동 선택") {
            post("/v1/tables/@{tt:frank-colors}/lecture/@{lecture:os}", "{}", `as` = "frank-new", label = "남은 색이 하나")
        }

    @Test
    fun `직접 만든 강의를 추가하고 고친다`() =
        parity("직접 만든 강의") {
            post(
                "/v1/tables/@{tt:alice-plan-b}/lecture",
                """{"course_title":"스터디","instructor":"","credit":0,"class_time_json":[{"day":5,"place":"도서관","startMinute":600,"endMinute":720}],"remark":"","color":{"bg":"#AABBCC","fg":"#000000"},"colorIndex":0}""",
                `as` = "alice",
            )
            post(
                "/v1/tables/@{tt:alice-plan-b}/lecture",
                """{"course_title":"겹치는 스터디","class_time_json":[{"day":4,"place":"","startMinute":800,"endMinute":850}],"colorIndex":2}""",
                `as` = "alice",
                label = "겹치는 직접 만든 강의",
            )
            put(
                "/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/club}",
                """{"course_title":"동아리 정기 회의","class_time_json":[{"day":4,"place":"학생회관","startMinute":1080,"endMinute":1140}],"colorIndex":3}""",
                `as` = "alice",
                label = "직접 만든 강의 수정",
            )
            put(
                "/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/os}",
                """{"course_title":"운영체제(내 메모)","colorIndex":4}""",
                `as` = "alice",
                label = "수강편람 강의 이름 바꾸기",
            )
            put("/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/os}/reset", `as` = "alice", label = "초기화")
            put("/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/club}/reset", `as` = "alice", label = "직접 만든 강의 초기화")
            delete("/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/ds}", `as` = "alice")
            get("/v1/tables/@{tt:alice-main}", `as` = "alice")
        }

    @Test
    fun `리마인더를 조회하고 바꾼다`() =
        parity("리마인더") {
            get("/v1/tables/@{tt:alice-main}/lecture/reminders", `as` = "alice")
            get("/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/os}/reminder", `as` = "alice")
            put("/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/ds}/reminder", """{"option":"TEN_MINUTES_BEFORE"}""", `as` = "alice")
            get("/v1/tables/@{tt:alice-main}/lecture/reminders", `as` = "alice")
            put("/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/os}/reminder", """{"option":"NONE"}""", `as` = "alice", label = "끄기")
            put(
                "/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/club}/reminder",
                """{"option":"ZERO_MINUTE"}""",
                `as` = "alice",
                label = "직접 만든 강의",
            )
            put(
                "/v1/tables/@{tt:alice-main}/lecture/@{tl:alice-main/os}/reminder",
                """{"option":"WEIRD"}""",
                `as` = "alice",
                label = "잘못된 option",
            )
        }

    // B7: 대표를 바꿔도 두 시간표의 updated_at은 그대로다(v1은 isPrimary만 바꿔 저장했다).
    @Test
    fun `대표 시간표를 바꿔도 수정 시각은 그대로다`() =
        parity("시간표: 대표 변경과 수정 시각") {
            post("/v1/tables/@{tt:alice-plan-b}/primary", `as` = "alice")
            get("/v1/tables/@{tt:alice-main}", `as` = "alice", label = "대표에서 풀린 시간표")
            get("/v1/tables/@{tt:alice-plan-b}", `as` = "alice", label = "새 대표")
            get("/v1/tables/recent", `as` = "alice")
            delete("/v1/tables/@{tt:alice-plan-b}/primary", `as` = "alice")
            get("/v1/tables/@{tt:alice-plan-b}", `as` = "alice", label = "대표 해제 뒤")
        }
}
