package com.wafflestudio.snutt.api.timetable

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimetableIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("timetable_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var timetableRepository: TimetableRepository

    @LocalServerPort
    var port = 0

    private lateinit var accessToken: String
    private lateinit var lectureIds: List<Long>

    @BeforeAll
    fun seedDatabase() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))

        data class Seed(
            val lecture: Lecture,
            val times: List<ClassPlaceAndTime>,
        )
        val lectureSeeds =
            listOf(
                Seed(
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
                    listOf(
                        ClassPlaceAndTime(DayOfWeek.MONDAY, "3-106", 570, 645),
                        ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "3-106", 570, 645),
                    ),
                ),
                Seed(
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
                    listOf(
                        ClassPlaceAndTime(DayOfWeek.MONDAY, "500-L301", 570, 645),
                        ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "500-L301", 570, 645),
                    ),
                ),
                Seed(
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
                    listOf(ClassPlaceAndTime(DayOfWeek.FRIDAY, "302-310-2", 1140, 1250)),
                ),
            )
        val lectures = lectureSeeds.map { it.lecture }
        lectureRepository.saveAll(lectures)
        val classTimes =
            lectureSeeds.flatMap { seed ->
                seed.times.map {
                    LectureClassTime(
                        lecture = seed.lecture,
                        day = it.day,
                        place = it.place,
                        startMinute = it.startMinute,
                        endMinute = it.endMinute,
                    )
                }
            }
        lectureClassTimeRepository.saveAll(classTimes)
        lectureIds = lectures.mapNotNull { it.id }

        val register =
            post("/v2/auth/register", """{"localId":"timetableuser","password":"password1","email":"tt@snu.ac.kr"}""")
        accessToken = body(register)["accessToken"].asString()
    }

    @BeforeEach
    fun cleanTimetables() {
        timetableRepository.deleteAll()
    }

    private fun client(): RestClient =
        RestClient
            .builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }) { _, _ -> }
            .defaultHeader("x-client-platform", "ios")
            .defaultHeader("x-client-key", "test-ios-key")
            .defaultHeader("Content-Type", "application/json")
            .build()

    private fun post(
        uri: String,
        body: String,
    ): ResponseEntity<String> =
        client()
            .post()
            .uri(uri)
            .headers { if (::accessToken.isInitialized) it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(String::class.java)

    private fun get(uri: String): ResponseEntity<String> =
        client()
            .get()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .toEntity(String::class.java)

    private fun put(
        uri: String,
        body: String,
    ): ResponseEntity<String> =
        client()
            .put()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(String::class.java)

    private fun patch(
        uri: String,
        body: String,
    ): ResponseEntity<String> =
        client()
            .patch()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(String::class.java)

    private fun delete(uri: String): ResponseEntity<String> =
        client()
            .delete()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .toEntity(String::class.java)

    private fun deleteWithBody(
        uri: String,
        body: String,
    ): ResponseEntity<String> =
        client()
            .method(HttpMethod.DELETE)
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(String::class.java)

    private val jsonMapper = JsonMapper.builder().build()

    private fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

    @Test
    fun `시간표 생성과 조회`() {
        val add = post("/v2/timetables", """{"year":2026,"semester":3,"title":"나의 시간표"}""")
        assertEquals(200, add.statusCode.value())
        assertEquals(1, body(add).size())
        val timetableId = body(add)[0]["id"].asString()

        val detail = get("/v2/timetables/$timetableId")
        assertEquals(200, detail.statusCode.value())
        assertEquals("나의 시간표", body(detail)["title"].asString())
        assertEquals(0, body(detail)["lectures"].size())
    }

    @Test
    fun `강의 추가 중복 겹침과 덮어쓰기`() {
        val timetableId = createTimetable("나의 시간표")
        val addLecture = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""")
        assertEquals(200, addLecture.statusCode.value())
        val lectures = body(addLecture)["lectures"]
        assertEquals(1, lectures.size())
        assertEquals("고급한국어", lectures[0]["courseTitle"].asString())

        val duplicate = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""")
        assertEquals(403, duplicate.statusCode.value())

        val overlap = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[1]}}""")
        assertEquals(403, overlap.statusCode.value())
        assertTrue(body(overlap)["displayMessage"].asString().contains("강의와 시간이 겹칩니다"))

        val forced = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[1]},"isForced":true}""")
        assertEquals(200, forced.statusCode.value())
        val afterForced = body(forced)["lectures"]
        assertEquals(1, afterForced.size())
        assertEquals("경영학을 위한 수학", afterForced[0]["courseTitle"].asString())

        val addAnother = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[2]}}""")
        assertEquals(200, addAnother.statusCode.value())
        assertEquals(2, body(addAnother)["lectures"].size())
    }

    @Test
    fun `custom 강의와 customization override`() {
        val timetableId = createTimetable("나의 시간표")

        val custom =
            post(
                "/v2/timetables/$timetableId/lectures/custom",
                """{"courseTitle":"직접만든강의","instructor":"나","credit":2,"classPlaceAndTimes":[{"day":4,"place":"","startMinute":570,"endMinute":660}]}""",
            )
        assertEquals(200, custom.statusCode.value())
        val customLecture = body(custom)["lectures"][0]
        assertEquals("직접만든강의", customLecture["courseTitle"].asString())
        assertFalse(customLecture.hasNonNull("lectureId"))

        post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""")
        val detail = get("/v2/timetables/$timetableId")
        val referenceLecture =
            body(detail)["lectures"].first { it.hasNonNull("lectureId") }
        val referenceLectureId = referenceLecture["id"].asString()

        val modified =
            patch(
                "/v2/timetables/$timetableId/lectures/$referenceLectureId",
                """{"courseTitle":"바뀐제목","isForced":false}""",
            )
        assertEquals(200, modified.statusCode.value())
        val modifiedLecture =
            body(modified)["lectures"].first { it["id"].asString() == referenceLectureId }
        assertEquals("바뀐제목", modifiedLecture["courseTitle"].asString())

        val reset = post("/v2/timetables/$timetableId/lectures/$referenceLectureId/reset", """{}""")
        assertEquals(200, reset.statusCode.value())
        val resetLecture =
            body(reset)["lectures"].first { it["id"].asString() == referenceLectureId }
        assertEquals("고급한국어", resetLecture["courseTitle"].asString())
    }

    @Test
    fun `리마인더 등록과 조회`() {
        val timetableId = createTimetable("나의 시간표")
        val add = post("/v2/timetables/$timetableId/lectures", """{"lectureId":${lectureIds[0]}}""")
        val timetableLectureId = body(add)["lectures"][0]["id"].asString()

        val set = put("/v2/timetables/$timetableId/lectures/$timetableLectureId/reminder", """{"option":"TEN_MINUTES_BEFORE"}""")
        assertEquals(200, set.statusCode.value())
        assertEquals("TEN_MINUTES_BEFORE", body(set)["option"].asString())

        val getReminders = get("/v2/timetables/$timetableId/lectures/reminders")
        val reminders = body(getReminders)
        assertEquals(1, reminders.size())
        assertEquals("TEN_MINUTES_BEFORE", reminders[0]["option"].asString())

        val clear = put("/v2/timetables/$timetableId/lectures/$timetableLectureId/reminder", """{"option":"NONE"}""")
        assertEquals("NONE", body(clear)["option"].asString())
        val afterClear = get("/v2/timetables/$timetableId/lectures/reminders")
        assertEquals("NONE", body(afterClear)[0]["option"].asString())
    }

    @Test
    fun `대표 시간표와 복사와 삭제`() {
        val first = createTimetable("나의 시간표")
        val second = createTimetable("두번째 시간표")

        val setPrimary = put("/v2/timetables/$first/primary", """{}""")
        assertEquals(200, setPrimary.statusCode.value())

        val copy = post("/v2/timetables/$first/copy", """{}""")
        assertEquals(200, copy.statusCode.value())
        val copied =
            body(copy).first { it["title"].asString() == "나의 시간표 (1)" }
        assertFalse(copied["isPrimary"].asBoolean())
        val copiedId = copied["id"].asString()

        assertEquals(200, delete("/v2/timetables/$copiedId").statusCode.value())
        assertEquals(200, delete("/v2/timetables/$second").statusCode.value())
        val deleteLast = delete("/v2/timetables/$first")
        assertEquals(400, deleteLast.statusCode.value())
    }

    @Test
    fun `커스텀 테마 생성과 시간표 적용`() {
        val theme =
            post(
                "/v2/themes",
                """{"name":"내테마","colors":[{"backgroundColor":"#FFFFFF","foregroundColor":"#000000"},{"backgroundColor":"#000000","foregroundColor":"#FFFFFF"}]}""",
            )
        assertEquals(200, theme.statusCode.value())
        val themeId = body(theme)["id"].asLong()

        val timetableId = createTimetable("나의 시간표")
        val apply =
            put(
                "/v2/timetables/$timetableId/theme",
                """{"themeId":$themeId}""",
            )
        assertEquals(200, apply.statusCode.value())
        assertEquals(themeId, body(apply)["themeId"].asLong())

        val basic =
            put(
                "/v2/timetables/$timetableId/theme",
                """{"themeId":2}""",
            )
        assertEquals(200, basic.statusCode.value())
        assertEquals(2L, body(basic)["themeId"].asLong())
    }

    @Test
    fun `북마크 추가 조회 삭제`() {
        val add = post("/v2/bookmarks/lecture", """{"lectureId":${lectureIds[0]}}""")
        assertEquals(200, add.statusCode.value())

        val getBookmarks = get("/v2/bookmarks?year=2026&semester=3")
        assertEquals(1, body(getBookmarks)["lectures"].size())

        val state = get("/v2/bookmarks/lectures/${lectureIds[0]}/state")
        assertEquals(true, body(state).asBoolean())

        val remove = deleteWithBody("/v2/bookmarks/lecture", """{"lectureId":${lectureIds[0]}}""")
        assertEquals(200, remove.statusCode.value())
        val afterRemove = get("/v2/bookmarks?year=2026&semester=3")
        assertEquals(0, body(afterRemove)["lectures"].size())
    }

    private fun createTimetable(title: String): String {
        val add = post("/v2/timetables", """{"year":2026,"semester":3,"title":"$title"}""")
        return body(add)[0]["id"].asString()
    }
}
