package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test

/** P1: 북마크, 빈자리 알림, 푸시 설정, 알림 목록, 계정 정보 수정. */
@ParityTest
class UserDataParityTest : AbstractParityTest() {
    @Test
    fun `북마크를 조회하고 바꾼다`() =
        parity("북마크") {
            get("/v1/bookmarks?year=2026&semester=3", `as` = "alice")
            get("/v1/bookmarks/lectures/@{lecture:ds}/state", `as` = "alice")
            post("/v1/bookmarks/lecture", """{"lecture_id":"@{lecture:os}"}""", `as` = "alice")
            post("/v1/bookmarks/lecture", """{"lecture_id":"@{lecture:os}"}""", `as` = "alice", label = "이미 담은 강의")
            delete("/v1/bookmarks/lecture", """{"lecture_id":"@{lecture:ds}"}""", `as` = "alice")
            get("/v1/bookmarks?year=2026&semester=3", `as` = "alice")
            get("/v1/bookmarks?year=2026&semester=3", `as` = "bob", label = "수강편람에서 사라진 강의를 담은 사용자")
            get("/v1/bookmarks?year=2026&semester=3", `as` = "carol", label = "북마크 없는 사용자")
        }

    @Test
    fun `빈자리 알림을 조회하고 바꾼다`() =
        parity("빈자리 알림") {
            get("/v1/vacancy-notifications/lectures", `as` = "alice")
            get("/v1/vacancy-notifications/lectures/@{lecture:online}/state", `as` = "alice")
            post("/v1/vacancy-notifications/lectures/@{lecture:ds}", `as` = "alice")
            post("/v1/vacancy-notifications/lectures/@{lecture:ds}", `as` = "alice", label = "중복")
            post("/v1/vacancy-notifications/lectures/@{lecture:os-spring}", `as` = "alice", label = "지난 학기 강의")
            delete("/v1/vacancy-notifications/lectures/@{lecture:online}", `as` = "alice")
            get("/v1/vacancy-notifications/lectures", `as` = "alice")
        }

    @Test
    fun `푸시 설정을 조회하고 저장한다`() =
        parity("푸시 설정") {
            get("/v1/push/preferences", `as` = "alice", label = "저장한 사용자")
            // 두 앱 모두 세 종류를 한 번에 보낸다(Android UserMappers, iOS PushNotificationAPIRepository).
            post(
                "/v1/push/preferences",
                """{"pushPreferences":[{"type":"LECTURE_UPDATE","isEnabled":true},{"type":"VACANCY_NOTIFICATION","isEnabled":false},{"type":"DIARY","isEnabled":true}]}""",
                `as` = "alice",
            )
            get("/v1/push/preferences", `as` = "alice", label = "저장 후 조회")
        }

    @Test
    fun `알림 목록을 받는다`() =
        parity("알림 목록") {
            get("/v1/notification?offset=0&limit=10", `as` = "alice")
            get("/v1/notification?offset=1&limit=2", `as` = "alice", label = "페이지")
            get("/v1/notification/count", `as` = "alice")
            get("/v1/notification?offset=0&limit=10&explicit=1", `as` = "alice", label = "읽음 처리")
            get("/v1/notification/count", `as` = "alice", label = "읽음 처리 후 개수")
            get("/v1/notification?offset=0&limit=10", `as` = "bob")
        }

    @Test
    fun `계정 정보를 바꾼다`() =
        parity("계정 정보 수정") {
            patch("/v1/users/me", """{"nickname":"새앨리스"}""", `as` = "alice")
            get("/v1/users/me", `as` = "alice")
            patch("/v1/users/me", """{"nickname":"새앨리스"}""", `as` = "alice", label = "같은 닉네임")
            put("/v1/user/password", """{"old_password":"password1","new_password":"password2"}""", `as` = "alice")
                .capture("token:alice-pw", "/token")
            get("/v1/users/me", `as` = "alice", label = "이전 토큰")
            get("/v1/users/me", `as` = "alice-pw", label = "새 토큰")
            put("/v1/user/password", """{"old_password":"x","new_password":"password3"}""", `as` = "carol", label = "소셜 전용 사용자")
            post("/v1/user/password", """{"id":"carol","password":"password9"}""", `as` = "carol", label = "소셜 사용자 로컬 추가")
            get("/v1/users/me/social_providers", `as` = "carol")
        }
}
