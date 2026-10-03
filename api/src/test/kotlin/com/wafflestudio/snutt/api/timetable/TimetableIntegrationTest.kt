package com.wafflestudio.snutt.api.timetable

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class TimetableIntegrationTest : AbstractMysqlIntegrationTest() {
    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    private lateinit var token: String
    private lateinit var lectureIds: List<Long>

    @BeforeEach
    fun seed() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        lectureIds =
            listOf(
                saveLecture(
                    Lecture(
                        year = 2026,
                        semester = Semester.AUTUMN,
                        courseNumber = "F27.301",
                        lectureNumber = "001",
                        courseTitle = "고급한국어",
                        instructor = "황현동",
                        department = "국어국문학과",
                        academicYear = "1학년",
                        category = "외국어",
                        classification = "교양",
                        credit = 3,
                        quota = 20,
                    ),
                    ClassPlaceAndTime(DayOfWeek.MONDAY, "3-106", 570, 645),
                    ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "3-106", 570, 645),
                ),
                saveLecture(
                    Lecture(
                        year = 2026,
                        semester = Semester.AUTUMN,
                        courseNumber = "F31.113",
                        lectureNumber = "001",
                        courseTitle = "경영학을 위한 수학",
                        instructor = "안명숙",
                        department = "수리과학부",
                        academicYear = "1학년",
                        category = "수학과학컴퓨팅",
                        classification = "교양",
                        credit = 3,
                        quota = 50,
                    ),
                    ClassPlaceAndTime(DayOfWeek.MONDAY, "500-L301", 570, 645),
                    ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "500-L301", 570, 645),
                ),
                saveLecture(
                    Lecture(
                        year = 2026,
                        semester = Semester.AUTUMN,
                        courseNumber = "400.320",
                        lectureNumber = "002",
                        courseTitle = "공학연구의 실습 1",
                        instructor = "이제희",
                        department = "컴퓨터공학부",
                        academicYear = "3학년",
                        classification = "전선",
                        credit = 1,
                        quota = 20,
                    ),
                    ClassPlaceAndTime(DayOfWeek.FRIDAY, "302-310-2", 1140, 1250),
                ),
            )
        token = register("timetableuser")
    }

    private fun saveLecture(
        lecture: Lecture,
        vararg times: ClassPlaceAndTime,
    ): Long = saveLectureWithTimes(lectureRepository, lectureClassTimeRepository, lecture, times.toList()).id!!

    private fun createTimetable(title: String): String {
        val add = post("/v2/timetables", """{"year":2026,"semester":3,"title":"$title"}""", token)
        return body(add).first { it["title"].asString() == title }["id"].asString()
    }

    @Test
    fun `시간표 생성과 조회`() {
        val add = post("/v2/timetables", """{"year":2026,"semester":3,"title":"테스트 시간표"}""", token)
        assertEquals(200, add.statusCode.value())
        val briefs = body(add)
        assertEquals(2, briefs.size())
        val timetableId = briefs.first { it["title"].asString() == "테스트 시간표" }["id"].asString()

        val detail = get("/v2/timetables/$timetableId", token)
        assertEquals(200, detail.statusCode.value())
        assertEquals("테스트 시간표", body(detail)["title"].asString())
        assertEquals(0, body(detail)["lectures"].size())
    }

    @Test
    fun `강의 추가 중복 겹침과 덮어쓰기`() {
        val timetableId = createTimetable("테스트 시간표")
        val addLecture = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""", token)
        assertEquals(200, addLecture.statusCode.value())
        val lectures = body(addLecture)["lectures"]
        assertEquals(1, lectures.size())
        assertEquals("고급한국어", lectures[0]["courseTitle"].asString())

        val duplicate = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""", token)
        assertEquals(409, duplicate.statusCode.value())

        val overlap = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[1]}}""", token)
        assertEquals(409, overlap.statusCode.value())
        assertTrue(body(overlap)["detail"].asString().contains("강의와 시간이 겹칩니다"))

        val forced = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[1]},"isForced":true}""", token)
        assertEquals(200, forced.statusCode.value())
        val afterForced = body(forced)["lectures"]
        assertEquals(1, afterForced.size())
        assertEquals("경영학을 위한 수학", afterForced[0]["courseTitle"].asString())

        val addAnother = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[2]}}""", token)
        assertEquals(200, addAnother.statusCode.value())
        assertEquals(2, body(addAnother)["lectures"].size())
    }

    @Test
    fun `custom 강의와 customization override`() {
        val timetableId = createTimetable("테스트 시간표")

        val custom =
            post(
                "/v2/timetables/$timetableId/lectures/custom",
                """{"courseTitle":"직접만든강의","instructor":"나","credit":2,"classPlaceAndTimes":[{"day":4,"place":"","startMinute":570,"endMinute":660}]}""",
                token,
            )
        assertEquals(200, custom.statusCode.value())
        val customLecture = body(custom)["lectures"][0]
        assertEquals("직접만든강의", customLecture["courseTitle"].asString())
        assertFalse(customLecture.hasNonNull("lectureId"))

        post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""", token)
        val detail = get("/v2/timetables/$timetableId", token)
        val referenceLectureId = body(detail)["lectures"].first { it.hasNonNull("lectureId") }["id"].asString()

        val modified =
            patch("/v2/timetables/$timetableId/lectures/$referenceLectureId", """{"courseTitle":"바뀐제목","isForced":false}""", token)
        assertEquals(200, modified.statusCode.value())
        val modifiedLecture = body(modified)["lectures"].first { it["id"].asString() == referenceLectureId }
        assertEquals("바뀐제목", modifiedLecture["courseTitle"].asString())

        val reset = post("/v2/timetables/$timetableId/lectures/$referenceLectureId/reset", "{}", token)
        assertEquals(200, reset.statusCode.value())
        val resetLecture = body(reset)["lectures"].first { it["id"].asString() == referenceLectureId }
        assertEquals("고급한국어", resetLecture["courseTitle"].asString())
    }

    @Test
    fun `리마인더 등록과 조회`() {
        val timetableId = createTimetable("테스트 시간표")
        val add = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""", token)
        val timetableLectureId = body(add)["lectures"][0]["id"].asString()

        val set = put("/v2/timetables/$timetableId/lectures/$timetableLectureId/reminder", """{"option":"TEN_MINUTES_BEFORE"}""", token)
        assertEquals(200, set.statusCode.value())
        assertEquals("TEN_MINUTES_BEFORE", body(set)["option"].asString())

        val reminders = body(get("/v2/timetables/$timetableId/lectures/reminders", token))
        assertEquals(1, reminders.size())
        assertEquals("TEN_MINUTES_BEFORE", reminders[0]["option"].asString())

        val clear = put("/v2/timetables/$timetableId/lectures/$timetableLectureId/reminder", """{"option":"NONE"}""", token)
        assertEquals("NONE", body(clear)["option"].asString())
        val afterClear = get("/v2/timetables/$timetableId/lectures/reminders", token)
        assertEquals("NONE", body(afterClear)[0]["option"].asString())
    }

    @Test
    fun `대표 시간표와 복사와 삭제`() {
        val first = createTimetable("테스트 시간표")
        val second = createTimetable("두번째 시간표")

        val setPrimary = put("/v2/timetables/$first/primary", "{}", token)
        assertEquals(200, setPrimary.statusCode.value())

        val copy = post("/v2/timetables/$first/copy", "{}", token)
        assertEquals(200, copy.statusCode.value())
        val copied = body(copy).first { it["title"].asString() == "테스트 시간표 (1)" }
        assertFalse(copied["isPrimary"].asBoolean())
        val copiedId = copied["id"].asString()
        val defaultId = body(copy).first { it["title"].asString() == "나의 시간표" }["id"].asString()

        assertEquals(200, delete("/v2/timetables/$copiedId", token).statusCode.value())
        assertEquals(200, delete("/v2/timetables/$second", token).statusCode.value())
        assertEquals(200, delete("/v2/timetables/$defaultId", token).statusCode.value())
        val deleteLast = delete("/v2/timetables/$first", token)
        assertEquals(400, deleteLast.statusCode.value())
    }

    @Test
    fun `커스텀 테마 생성과 시간표 적용`() {
        val theme =
            post(
                "/v2/themes",
                """{"name":"내테마","colors":[{"backgroundColor":"#FFFFFF","foregroundColor":"#000000"},{"backgroundColor":"#000000","foregroundColor":"#FFFFFF"}]}""",
                token,
            )
        assertEquals(200, theme.statusCode.value())
        val themeId = body(theme)["id"].asLong()

        val timetableId = createTimetable("테스트 시간표")
        val apply = put("/v2/timetables/$timetableId/theme", """{"themeId":$themeId}""", token)
        assertEquals(200, apply.statusCode.value())
        assertEquals(themeId, body(apply)["themeId"].asLong())

        val basic = put("/v2/timetables/$timetableId/theme", """{"themeId":2}""", token)
        assertEquals(200, basic.statusCode.value())
        assertEquals(2L, body(basic)["themeId"].asLong())
    }

    @Test
    fun `북마크 추가 조회 삭제`() {
        val add = post("/v2/bookmarks/lectures/${lectureIds[0]}", "{}", token)
        assertEquals(200, add.statusCode.value())

        val bookmarks = get("/v2/bookmarks?year=2026&semester=3", token)
        assertEquals(1, body(bookmarks)["lectures"].size())

        val state = get("/v2/bookmarks/lectures/${lectureIds[0]}/state", token)
        assertEquals(true, body(state).asBoolean())

        val remove = delete("/v2/bookmarks/lectures/${lectureIds[0]}", token)
        assertEquals(200, remove.statusCode.value())
        val afterRemove = get("/v2/bookmarks?year=2026&semester=3", token)
        assertEquals(0, body(afterRemove)["lectures"].size())
    }
}
