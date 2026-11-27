package com.wafflestudio.snutt.parity.scenarios

import com.wafflestudio.snutt.core.test.ParityTest
import com.wafflestudio.snutt.parity.AbstractParityTest
import org.junit.jupiter.api.Test

/** P2: 테마 목록·추가·수정·복사·삭제와 테마 마켓. */
@ParityTest
class ThemeParityTest : AbstractParityTest() {
    @Test
    fun `이관된 테마를 조회한다`() =
        parity("테마 조회") {
            get("/v1/themes", `as` = "alice")
            get("/v1/themes/@{theme:alice-custom}", `as` = "alice")
            get("/v1/themes", `as` = "carol", label = "사용자 테마 없는 사용자")
            get("/v1/themes/best?page=1", `as` = "alice")
            get("/v1/themes/friends?page=1", `as` = "alice")
            post("/v1/themes/search?query=${java.net.URLEncoder.encode("공개", Charsets.UTF_8)}", `as` = "alice")
        }

    @Test
    fun `테마를 만들고 고치고 복사하고 지운다`() =
        parity("테마 CRUD") {
            post(
                "/v1/themes",
                """{"name":"새 테마","colors":[{"bg":"#101010","fg":"#FFFFFF"},{"bg":"#202020","fg":"#FFFFFF"}]}""",
                `as` = "carol",
            ).capture("theme:carol-new", "/id")
            patch("/v1/themes/@{theme:carol-new}", """{"name":"고친 테마"}""", `as` = "carol")
            post("/v1/themes/@{theme:carol-new}/copy", `as` = "carol")
            get("/v1/themes", `as` = "carol")
            post("/v1/themes/@{theme:carol-new}/default", `as` = "carol")
            post("/v1/themes/basic/3/default", `as` = "carol", label = "기본 테마를 기본으로")
            delete("/v1/themes/@{theme:carol-new}", `as` = "carol")
            get("/v1/themes/@{theme:carol-new}", `as` = "carol", label = "지운 테마")
            patch("/v1/themes/@{theme:alice-custom}", """{"name":"남의 테마"}""", `as` = "carol", label = "남의 테마 수정")
        }
}
