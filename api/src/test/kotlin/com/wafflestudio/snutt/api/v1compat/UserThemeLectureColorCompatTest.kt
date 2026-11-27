package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode

/**
 * 사용자 테마 시간표의 강의 색. v1compat은 팔레트 색 강의를 colorIndex 0 + 그 색으로 내보내고(v1도 그렇게 저장했다),
 * 두 구앱은 강의를 고칠 때 받은 colorIndex 0 + color를 그대로 되돌려 보낸다(Android LectureMappers.lectureColorToColorIndex,
 * iOS LectureAPIRepository.modifyLecture). 그 색이 팔레트에 있으면 팔레트 번호로 남아 이후 테마 색을 바꿔도 따라가야 한다.
 */
class UserThemeLectureColorCompatTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var userRepository: UserRepository

    private lateinit var legacyToken: String
    private lateinit var v2Token: String
    private var themeId: Long = 0
    private var timetableId: Long = 0
    private lateinit var first: TimetableLecture
    private lateinit var second: TimetableLecture

    @BeforeEach
    fun seed() {
        legacyToken = v1Register("themecolor").first
        v2Token = v2Login("themecolor")
        // 같은 색이 두 번 들어 있는 팔레트(0번과 2번이 같다)
        val created =
            post(
                "/v2/themes",
                """{"name":"겹치는 색","colors":[{"backgroundColor":"#1d99e8","foregroundColor":"#ffffff"},""" +
                    """{"backgroundColor":"#7ac73f","foregroundColor":"#ffffff"},{"backgroundColor":"#1d99e8","foregroundColor":"#ffffff"}]}""",
                v2Token,
            )
        assertEquals(200, created.statusCode.value(), "body=${created.body}")
        themeId = body(created).items().single { it["name"].asString() == "겹치는 색" }["id"].asLong()

        val user = userRepository.findByLocalIdAndActiveTrue("themecolor")!!
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val timetable = fixtures.timetable(user, autumn, themeId = themeId)
        timetableId = timetable.id!!
        val os = fixtures.lecture(autumn, courseTitle = "운영체제", times = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "", 600, 660)))
        val ds = fixtures.lecture(autumn, courseTitle = "자료구조", times = listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "", 600, 660)))
        first = fixtures.addLecture(timetable, os, paletteIndex = 0)
        second = fixtures.addLecture(timetable, ds, paletteIndex = 1)
    }

    private fun legacyLecture(timetableLecture: TimetableLecture): JsonNode =
        body(v1Get("/v1/tables/$timetableId", legacyToken))["lecture_list"].items().single {
            it["_id"].asString() ==
                timetableLecture.id.toString()
        }

    private fun v2Lecture(timetableLecture: TimetableLecture): JsonNode =
        body(get("/v2/timetables/$timetableId", v2Token))["lectures"].items().single { it["id"].asLong() == timetableLecture.id }

    /** 구앱이 강의 상세에서 메모만 고치고 저장한다. 색은 받은 그대로(Android는 대문자 #RRGGBB로) 되돌려 보낸다. */
    private fun legacyModifyRemark(
        timetableLecture: TimetableLecture,
        color: JsonNode,
    ) {
        val response =
            v1Send(
                HttpMethod.PUT,
                "/v1/tables/$timetableId/lecture/${timetableLecture.id}",
                """{"remark":"메모","colorIndex":0,"color":{"bg":"${color["bg"].asString().uppercase()}","fg":"${color["fg"].asString().uppercase()}"}}""",
                legacyToken,
            )
        assertEquals(200, response.statusCode.value(), "body=${response.body}")
    }

    @Test
    fun `구앱이 받은 팔레트 색을 되돌려 보내면 팔레트 번호로 남아 테마 색 변경을 따라간다`() {
        val shown = legacyLecture(second)
        assertEquals(0, shown["colorIndex"].asInt())
        assertEquals("#7ac73f", shown["color"]["bg"].asString())

        legacyModifyRemark(second, shown["color"])

        val stored = v2Lecture(second)
        assertTrue(stored["customColor"]?.isNull ?: true, "body=$stored")
        assertEquals(1, stored["paletteIndex"].asInt())
        assertEquals("메모", stored["remark"].asString())
    }

    @Test
    fun `팔레트에 같은 색이 여럿이면 v1처럼 마지막 칸을 따라간다`() {
        // first는 0번 칸에 있지만 0번과 2번이 같은 색이다. v1 modifyTheme은 같은 색이면 마지막 칸의 변경을 따랐다.
        legacyModifyRemark(first, legacyLecture(first)["color"])

        val stored = v2Lecture(first)
        assertTrue(stored["customColor"]?.isNull ?: true, "body=$stored")
        assertEquals(2, stored["paletteIndex"].asInt())

        // 0번 칸만 바꾸면 그대로, 2번 칸을 바꾸면 따라간다
        modifyPalette(listOf("#e54459", "#7ac73f", "#1d99e8"))
        assertEquals("#1d99e8", legacyLecture(first)["color"]["bg"].asString())
        modifyPalette(listOf("#e54459", "#7ac73f", "#f58d3d"))
        assertEquals("#f58d3d", legacyLecture(first)["color"]["bg"].asString())
    }

    private fun modifyPalette(backgrounds: List<String>) {
        val colors = backgrounds.joinToString(",") { """{"backgroundColor":"$it","foregroundColor":"#ffffff"}""" }
        val modified = patch("/v2/themes/$themeId", """{"colors":[$colors]}""", v2Token)
        assertEquals(200, modified.statusCode.value(), "body=${modified.body}")
    }

    @Test
    fun `팔레트에 없는 색을 보내면 v1처럼 그 색으로 저장한다`() {
        val response =
            v1Send(
                HttpMethod.PUT,
                "/v1/tables/$timetableId/lecture/${second.id}",
                """{"colorIndex":0,"color":{"bg":"#123456","fg":"#FFFFFF"}}""",
                legacyToken,
            )
        assertEquals(200, response.statusCode.value(), "body=${response.body}")

        val stored = v2Lecture(second)
        assertEquals("#123456", stored["customColor"]["backgroundColor"].asString())
        assertEquals("#123456", legacyLecture(second)["color"]["bg"].asString())
    }
}
