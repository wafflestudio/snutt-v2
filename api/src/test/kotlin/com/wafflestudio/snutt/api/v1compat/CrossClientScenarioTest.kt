package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode

/**
 * 같은 사용자가 구앱(v1compat)과 신앱(v2)을 번갈아 쓰는 2단계 전환 시나리오.
 * 한쪽에서 쓴 것을 다른 쪽에서 읽고 고친 뒤 다시 처음 쪽에서 읽어, 각 단계에서 두 표현이 같은 데이터를 가리키는지 본다.
 */
class CrossClientScenarioTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var userRepository: UserRepository

    private lateinit var lecture: Lecture
    private lateinit var otherLecture: Lecture

    @BeforeEach
    fun seed() {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val course = courseRepository.save(Course(courseNumber = "4190.310", instructor = "이교수", title = "운영체제"))
        lecture =
            fixtures.lecture(
                autumn,
                courseTitle = "운영체제",
                courseNumber = "4190.310",
                times = listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 600, 675)),
            ) {
                instructor = "이교수"
                courseTitleEn = "Operating Systems"
                courseId = course.id
            }
        otherLecture =
            fixtures.lecture(
                autumn,
                courseTitle = "컴파일러",
                courseNumber = "4190.409",
                credit = 4,
                times = listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "302-208", 780, 855)),
            )
    }

    private fun JsonNode.lectureByTitle(title: String): JsonNode = items().single { it["course_title"].asString() == title }

    @Test
    fun `구앱에서 만든 시간표를 신앱이 고치면 구앱에 그대로 보인다`() {
        val (legacyToken, userId) = v1Register("crossold")

        // 1. 구앱: 시간표 생성, 강의 추가, 기본 테마 "모던"(v1 theme 2) 지정
        val created = v1Post("/v1/tables", """{"year":2026,"semester":3,"title":"구앱 시간표"}""", legacyToken)
        val timetableId = body(created).items().single { it["title"].asString() == "구앱 시간표" }["_id"].asString()
        assertEquals(200, v1Post("/v1/tables/$timetableId/lecture/${lecture.id}", "{}", legacyToken).statusCode.value())
        val themed = v1Put("/v1/tables/$timetableId/theme", """{"theme":2}""", legacyToken)
        assertEquals(2, body(themed)["theme"].asInt(), "body=${themed.body}")

        // 2. 신앱: 같은 시간표, 같은 강의, 같은 테마(빌트인 modern)를 본다
        val v2Token = v2Login("crossold")
        assertEquals(userId, body(get("/v2/users/me", v2Token))["id"].asString())
        val v2Timetable = body(get("/v2/timetables/$timetableId", v2Token))
        assertEquals("구앱 시간표", v2Timetable["title"].asString())
        val modern = body(get("/v2/themes/${v2Timetable["themeId"].asLong()}", v2Token))
        assertEquals("modern", modern["builtinCode"].asString())
        assertEquals(listOf(lecture.id), v2Timetable["lectures"].items().map { it["lectureId"].asLong() })

        // 3. 신앱: 제목 변경, 직접 만든 강의 추가, 새 사용자 테마를 만들어 지정
        patch("/v2/timetables/$timetableId", """{"title":"신앱에서 바꾼 제목"}""", v2Token)
        val custom =
            post(
                "/v2/timetables/$timetableId/lectures/custom",
                """{"courseTitle":"스터디","credit":1,"classPlaceAndTimes":[{"day":3,"place":"도서관","startMinute":1140,"endMinute":1200}]}""",
                v2Token,
            )
        assertEquals(200, custom.statusCode.value(), "body=${custom.body}")
        val themeId =
            body(
                post(
                    "/v2/themes",
                    """{"name":"내 테마","colors":[{"backgroundColor":"#111111","foregroundColor":"#eeeeee"},""" +
                        """{"backgroundColor":"#222222","foregroundColor":"#dddddd"}]}""",
                    v2Token,
                ),
            ).items().single { it["name"].asString() == "내 테마" }["id"].asLong()
        assertEquals(200, put("/v2/timetables/$timetableId/theme", """{"themeId":$themeId}""", v2Token).statusCode.value())

        // 4. 구앱: 바뀐 제목, 두 강의, 사용자 테마(theme 0 + themeId)와 테마 색이 보인다
        val legacy = body(v1Get("/v1/tables/$timetableId", legacyToken))
        assertEquals("신앱에서 바꾼 제목", legacy["title"].asString())
        assertEquals(userId, legacy["user_id"].asString())
        assertEquals(0, legacy["theme"].asInt())
        assertEquals(themeId.toString(), legacy["themeId"].asString())
        val lectures = legacy["lecture_list"]
        assertEquals(2, lectures.size())
        val catalogLecture = lectures.lectureByTitle("운영체제")
        assertEquals(lecture.id.toString(), catalogLecture["lecture_id"].asString())
        assertEquals(lecture.courseId, catalogLecture["snuttEvLecture"]["evLectureId"].asLong())
        val customLecture = lectures.lectureByTitle("스터디")
        assertFalse(customLecture.has("lecture_id"))
        assertEquals(1140, customLecture["class_time_json"][0]["startMinute"].asInt())
        assertEquals("19:00", customLecture["class_time_json"][0]["start_time"].asString())
        // 사용자 테마의 강의는 colorIndex 0 + 테마 팔레트 색으로 나간다
        lectures.items().forEach { assertEquals(0, it["colorIndex"].asInt()) }
        assertEquals(
            setOf("#111111", "#222222"),
            lectures.items().map { it["color"]["bg"].asString() }.toSet(),
        )

        val brief = body(v1Get("/v1/tables", legacyToken)).items().single { it["_id"].asString() == timetableId }
        assertEquals("신앱에서 바꾼 제목", brief["title"].asString())
        assertEquals(lecture.credit!! + 1, brief["total_credit"].asInt())

        val theme = body(v1Get("/v1/themes", legacyToken)).items().single { it["id"]?.asString() == themeId.toString() }
        assertEquals("PRIVATE", theme["status"].asString())
        assertEquals(true, theme["isCustom"].asBoolean())
        assertEquals("#111111", theme["colors"][0]["bg"].asString())
    }

    @Test
    fun `신앱에서 만든 시간표를 구앱이 고치면 신앱에 그대로 보인다`() {
        val v2Token = register("crossnew")
        val created = post("/v2/timetables", """{"year":2026,"semester":3,"title":"신앱 시간표"}""", v2Token)
        val timetableId = body(created).items().single { it["title"].asString() == "신앱 시간표" }["id"].asString()
        post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lecture.id}}""", v2Token)

        // 구앱으로 같은 계정에 로그인하면 신앱 데이터가 v1 형식으로 보인다
        val login = v1Post("/v1/auth/login_local", """{"id":"crossnew","password":"password1"}""")
        assertEquals(200, login.statusCode.value(), "body=${login.body}")
        val legacyToken = body(login)["token"].asString()
        val recent = body(v1Get("/v1/tables/recent", legacyToken))
        assertEquals(timetableId, recent["_id"].asString())
        assertEquals(3, recent["semester"].asInt())
        assertEquals(lecture.id.toString(), recent["lecture_list"].lectureByTitle("운영체제")["lecture_id"].asString())

        // 구앱: 강의 추가, 기본 테마 "가을"(v1 theme 1), 운영체제 강의 시간 수정(override)
        assertEquals(200, v1Post("/v1/tables/$timetableId/lecture/${otherLecture.id}", "{}", legacyToken).statusCode.value())
        v1Put("/v1/tables/$timetableId/theme", """{"theme":1}""", legacyToken)
        val timetableLectureId = recent["lecture_list"].lectureByTitle("운영체제")["_id"].asString()
        val modified =
            v1Put(
                "/v1/tables/$timetableId/lecture/$timetableLectureId",
                """{"class_time_json":[{"day":4,"place":"301-102","startMinute":540,"endMinute":615}]}""",
                legacyToken,
            )
        assertEquals(200, modified.statusCode.value(), "body=${modified.body}")

        // 신앱: 두 강의, 빌트인 "fall" 테마, 바뀐 시간이 보인다
        val v2Timetable = body(get("/v2/timetables/$timetableId", v2Token))
        assertEquals(setOf(lecture.id, otherLecture.id), v2Timetable["lectures"].items().map { it["lectureId"].asLong() }.toSet())
        assertEquals("fall", body(get("/v2/themes/${v2Timetable["themeId"].asLong()}", v2Token))["builtinCode"].asString())
        val overridden = v2Timetable["lectures"].items().single { it["lectureId"].asLong() == lecture.id }
        assertEquals(540, overridden["classPlaceAndTimes"][0]["startMinute"].asInt())
        assertEquals("301-102", overridden["classPlaceAndTimes"][0]["place"].asString())

        // 신앱이 override를 되돌리면 구앱도 수강편람 시간을 본다
        val reset = post("/v2/timetables/$timetableId/lectures/$timetableLectureId/reset", "{}", v2Token)
        assertEquals(200, reset.statusCode.value(), "body=${reset.body}")
        val legacyAfterReset = body(v1Get("/v1/tables/$timetableId", legacyToken))["lecture_list"].lectureByTitle("운영체제")
        assertEquals(600, legacyAfterReset["class_time_json"][0]["startMinute"].asInt())
        assertEquals(0, legacyAfterReset["class_time_json"][0]["day"].asInt())
    }

    @Test
    fun `구앱에서 쓴 강의평을 신앱이 고치고 구앱이 다시 읽는다`() {
        val (legacyToken, userId) = v1Register("crosseval")
        val user = userRepository.findByLocalIdAndActiveTrue("crosseval")!!
        user.isEmailVerified = true
        userRepository.save(user)

        val written =
            v1Post(
                "/v1/ev-service/v1/semester-lectures/${lecture.id}/evaluations",
                """{"content":"과제가 많지만 운영체제를 제대로 배울 수 있는 강의입니다","grade_satisfaction":4.0,"teaching_skill":5.0,""" +
                    """"gains":5.0,"life_balance":2.0,"rating":4.0}""",
                legacyToken,
            )
        assertEquals(200, written.statusCode.value(), "body=${written.body}")
        val evaluationId = body(written)["id"].asLong()
        assertEquals(userId, body(written)["user_id"].asString())

        // 신앱: 같은 id의 강의평이 같은 과목(EV lecture id = v2 course id)에 붙어 있다
        val v2Token = v2Login("crosseval")
        val v2Evaluation = body(get("/v2/evaluations/$evaluationId", v2Token))
        assertEquals(lecture.courseId, v2Evaluation["courseId"].asLong())
        assertEquals("과제가 많지만 운영체제를 제대로 배울 수 있는 강의입니다", v2Evaluation["content"].asString())
        assertEquals(4.0, v2Evaluation["rating"].asDouble())

        val updated = patch("/v2/evaluations/$evaluationId", """{"rating":2.5,"content":"신앱에서 고친 강의평입니다. 과제가 정말 많습니다"}""", v2Token)
        assertEquals(200, updated.statusCode.value(), "body=${updated.body}")

        // 구앱: 수정된 내용, 과목 요약 집계, 내 강의평 목록이 모두 바뀐 값을 본다
        val legacy = body(v1Get("/v1/ev-service/v1/evaluations/$evaluationId", legacyToken))
        assertEquals(2.5, legacy["rating"].asDouble())
        assertEquals("신앱에서 고친 강의평입니다. 과제가 정말 많습니다", legacy["content"].asString())
        assertEquals(true, legacy["is_modifiable"].asBoolean())
        val summary = body(v1Get("/v1/ev-service/v1/lectures/${lecture.courseId}/evaluation-summary", legacyToken))
        assertEquals(2.5, summary["evaluation"]["avg_rating"].asDouble(), "body=$summary")
        assertEquals(1, summary["evaluation"]["evaluation_count"].asInt(), "body=$summary")
        val mine = body(v1Get("/v1/ev-service/v1/lectures/${lecture.courseId}/evaluations/users/me", legacyToken))
        assertEquals(listOf(evaluationId), mine["evaluations"].items().map { it["id"].asLong() })

        // 신앱에서 지우면 구앱 목록에서도 사라진다
        assertEquals(200, delete("/v2/evaluations/$evaluationId", v2Token).statusCode.value())
        assertTrue(body(v1Get("/v1/ev-service/v1/lectures/${lecture.courseId}/evaluations/users/me", legacyToken))["evaluations"].isEmpty)
        assertV1Error(v1Get("/v1/ev-service/v1/evaluations/$evaluationId", legacyToken), 404, 24005)
    }

    @Test
    fun `구앱에서 고친 닉네임과 북마크를 신앱이 보고 신앱의 변경을 구앱이 본다`() {
        val (legacyToken, _) = v1Register("crossprofile")
        val v2Token = v2Login("crossprofile")

        val renamed = v1Send(HttpMethod.PATCH, "/v1/users/me", """{"nickname":"구앱닉"}""", legacyToken)
        assertEquals(200, renamed.statusCode.value(), "body=${renamed.body}")
        assertEquals("구앱닉", body(get("/v2/users/me", v2Token))["nickname"].asString())

        v1Post("/v1/bookmarks/lecture", """{"lecture_id":"${lecture.id}"}""", legacyToken)
        val v2Bookmarks = body(get("/v2/bookmarks?year=2026&semester=3", v2Token))
        assertEquals(listOf(lecture.id), v2Bookmarks["lectures"].items().map { it["id"].asLong() }, "body=$v2Bookmarks")

        assertEquals(200, delete("/v2/bookmarks/lectures/${lecture.id}", v2Token).statusCode.value())
        val legacyBookmarks = body(v1Get("/v1/bookmarks?year=2026&semester=3", legacyToken))
        assertTrue(legacyBookmarks["lectures"].isEmpty, "body=$legacyBookmarks")
    }
}
