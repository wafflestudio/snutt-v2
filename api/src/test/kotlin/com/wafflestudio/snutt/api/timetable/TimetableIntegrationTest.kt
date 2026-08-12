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
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

/**
 * v1 TimetableIntegTest 시나리오의 v2 이식: 시간표 CRUD, 강의 추가/중복/겹침/덮어쓰기,
 * custom 강의, customization override, 리마인더, 테마, 북마크 (PLAN.md §7 M3)
 */
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
    private lateinit var lectureIds: List<String>

    @BeforeAll
    fun seedDatabase() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))

        // L1: 월 570-660, L2: 월 600-690(겹침), L3: 화 780-870(안 겹침)
        val lectureSeeds =
            listOf(
                Triple("시간표강의1", "4190.001", listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "302-101", 570, 660))),
                Triple("시간표강의2", "4190.002", listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "302-101", 600, 690))),
                Triple("시간표강의3", "4190.003", listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "43-1-302", 780, 870))),
            )
        val lectures =
            lectureSeeds.map { (title, courseNumber, times) ->
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = courseNumber,
                    lectureNumber = "001",
                    courseTitle = title,
                    instructor = "테스트교수",
                    department = "컴퓨터공학부",
                    classification = "전선",
                    credit = 3,
                    classPlaceAndTime = times,
                )
            }
        lectureRepository.saveAll(lectures)
        val classTimes =
            lectures.flatMap { lecture ->
                lecture.classPlaceAndTime.map {
                    LectureClassTime(
                        lecture = lecture,
                        day = it.day,
                        place = it.place,
                        startMinute = it.startMinute,
                        endMinute = it.endMinute,
                    )
                }
            }
        lectureClassTimeRepository.saveAll(classTimes)
        lectureIds = lectures.map { it.externalId }

        val register =
            post("/v2/auth/register", """{"localId":"timetableuser","password":"password1","email":"tt@snu.ac.kr"}""")
        accessToken = asMap(register)["accessToken"] as String
    }

    // 공유 MySQL이므로 다음 테스트 클래스에 데이터를 남기지 않는다.

    // 테스트 간 시간표 잔여물로 중복 제목 403이 나지 않도록 각 테스트 전에 비운다
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

    @Suppress("UNCHECKED_CAST")
    private fun post(
        uri: String,
        body: String,
    ): ResponseEntity<Any> =
        client()
            .post()
            .uri(uri)
            .headers { if (::accessToken.isInitialized) it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun get(uri: String): ResponseEntity<Any> =
        client()
            .get()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun put(
        uri: String,
        body: String,
    ): ResponseEntity<Any> =
        client()
            .put()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun patch(
        uri: String,
        body: String,
    ): ResponseEntity<Any> =
        client()
            .patch()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun delete(uri: String): ResponseEntity<Any> =
        client()
            .delete()
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun deleteWithBody(
        uri: String,
        body: String,
    ): ResponseEntity<Any> =
        client()
            .method(org.springframework.http.HttpMethod.DELETE)
            .uri(uri)
            .headers { it.setBearerAuth(accessToken) }
            .body(body)
            .retrieve()
            .toEntity(Any::class.java)

    private fun asList(response: ResponseEntity<Any>): List<Map<String, Any?>> = response.body as List<Map<String, Any?>>

    private fun asMap(response: ResponseEntity<Any>): Map<String, Any?> = response.body as Map<String, Any?>

    @Test
    fun `시간표 생성과 조회`() {
        val add = post("/v2/timetables", """{"year":2026,"semester":3,"title":"나의 시간표"}""")
        assertEquals(200, add.statusCode.value())
        assertEquals(1, asList(add).size)
        val timetableId = asList(add)[0]["id"] as String

        val detail = get("/v2/timetables/$timetableId")
        assertEquals(200, detail.statusCode.value())
        assertEquals("나의 시간표", asMap(detail)["title"])
        assertEquals(emptyList<Any>(), asMap(detail)["lectures"])
    }

    @Test
    fun `강의 추가 중복 겹침과 덮어쓰기`() {
        val timetableId = createTimetable("나의 시간표")
        val addLecture = post("/v2/timetables/$timetableId/lectures", """{"lectureId":"${lectureIds[0]}"}""")
        assertEquals(200, addLecture.statusCode.value())
        val lectures = asMap(addLecture)["lectures"] as List<*>
        assertEquals(1, lectures.size)
        assertEquals("시간표강의1", (lectures[0] as Map<*, *>)["courseTitle"])

        // 중복 추가
        val duplicate = post("/v2/timetables/$timetableId/lectures", """{"lectureId":"${lectureIds[0]}"}""")
        assertEquals(403, duplicate.statusCode.value())
        assertEquals(0x3004, asMap(duplicate)["errcode"])

        // 시간 겹침 → 403 + 확인 메시지
        val overlap = post("/v2/timetables/$timetableId/lectures", """{"lectureId":"${lectureIds[1]}"}""")
        assertEquals(403, overlap.statusCode.value())
        assertEquals(0x300C, asMap(overlap)["errcode"])
        assertTrue((asMap(overlap)["displayMessage"] as String).contains("강의와 시간이 겹칩니다"))

        // isForced → 덮어쓰기
        val forced = post("/v2/timetables/$timetableId/lectures", """{"lectureId":"${lectureIds[1]}","isForced":true}""")
        assertEquals(200, forced.statusCode.value())
        val afterForced = asMap(forced)["lectures"] as List<*>
        assertEquals(1, afterForced.size)
        assertEquals("시간표강의2", (afterForced[0] as Map<*, *>)["courseTitle"])

        // 겹치지 않는 강의 추가
        val addAnother = post("/v2/timetables/$timetableId/lectures", """{"lectureId":"${lectureIds[2]}"}""")
        assertEquals(200, addAnother.statusCode.value())
        assertEquals(2, (asMap(addAnother)["lectures"] as List<*>).size)
    }

    @Test
    fun `custom 강의와 customization override`() {
        val timetableId = createTimetable("나의 시간표")

        // custom 강의 추가
        val custom =
            post(
                "/v2/timetables/$timetableId/lectures/custom",
                """{"courseTitle":"직접만든강의","instructor":"나","credit":2,"classPlaceAndTime":[{"day":4,"place":"","startMinute":570,"endMinute":660}]}""",
            )
        assertEquals(200, custom.statusCode.value())
        val customLecture = (asMap(custom)["lectures"] as List<*>)[0] as Map<*, *>
        assertEquals("직접만든강의", customLecture["courseTitle"])
        assertEquals(null, customLecture["lectureId"])

        // lecture 참조 강의 추가 후 제목 override
        post("/v2/timetables/$timetableId/lectures", """{"lectureId":"${lectureIds[0]}"}""")
        val detail = get("/v2/timetables/$timetableId")
        val referenceLecture =
            (asMap(detail)["lectures"] as List<*>).first { (it as Map<*, *>)["lectureId"] != null } as Map<*, *>
        val referenceLectureId = referenceLecture["id"] as String

        val modified =
            patch(
                "/v2/timetables/$timetableId/lectures/$referenceLectureId",
                """{"courseTitle":"바뀐제목","isForced":false}""",
            )
        assertEquals(200, modified.statusCode.value())
        val modifiedLecture =
            (asMap(modified)["lectures"] as List<*>).first { (it as Map<*, *>)["id"] == referenceLectureId } as Map<*, *>
        assertEquals("바뀐제목", modifiedLecture["courseTitle"])

        // reset → 원래 제목 복원
        val reset = post("/v2/timetables/$timetableId/lectures/$referenceLectureId/reset", """{}""")
        assertEquals(200, reset.statusCode.value())
        val resetLecture =
            (asMap(reset)["lectures"] as List<*>).first { (it as Map<*, *>)["id"] == referenceLectureId } as Map<*, *>
        assertEquals("시간표강의1", resetLecture["courseTitle"])
    }

    @Test
    fun `리마인더 등록과 조회`() {
        val timetableId = createTimetable("나의 시간표")
        val add = post("/v2/timetables/$timetableId/lectures", """{"lectureId":"${lectureIds[0]}"}""")
        val timetableLectureId = ((asMap(add)["lectures"] as List<*>)[0] as Map<*, *>)["id"] as String

        val set = put("/v2/timetables/$timetableId/lectures/$timetableLectureId/reminder", """{"option":"TEN_MINUTES_BEFORE"}""")
        assertEquals(200, set.statusCode.value())
        assertEquals("TEN_MINUTES_BEFORE", asMap(set)["option"])

        val getReminders = get("/v2/timetables/$timetableId/lectures/reminders")
        val reminders = getReminders.body as List<*>
        assertEquals(1, reminders.size)
        assertEquals("TEN_MINUTES_BEFORE", (reminders[0] as Map<*, *>)["option"])

        // '없음' 옵션 → 삭제
        val clear = put("/v2/timetables/$timetableId/lectures/$timetableLectureId/reminder", """{"option":"NONE"}""")
        assertEquals("NONE", asMap(clear)["option"])
        val afterClear = get("/v2/timetables/$timetableId/lectures/reminders")
        assertEquals("NONE", ((afterClear.body as List<*>)[0] as Map<*, *>)["option"])
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
            asList(copy).first { it["title"] == "나의 시간표 (1)" }
        assertFalse(copied["isPrimary"] as Boolean)
        val copiedId = copied["id"] as String

        // 하나만 남은 시간표는 삭제 불가
        assertEquals(200, delete("/v2/timetables/$copiedId").statusCode.value())
        assertEquals(200, delete("/v2/timetables/$second").statusCode.value())
        val deleteLast = delete("/v2/timetables/$first")
        assertEquals(400, deleteLast.statusCode.value())
        assertEquals(40010, asMap(deleteLast)["errcode"])
    }

    @Test
    fun `커스텀 테마 생성과 시간표 적용`() {
        val theme =
            post(
                "/v2/themes",
                """{"name":"내테마","colorList":[{"backgroundColor":"#FFFFFF","foregroundColor":"#000000"},{"backgroundColor":"#000000","foregroundColor":"#FFFFFF"}]}""",
            )
        assertEquals(200, theme.statusCode.value())
        val themeId = asMap(theme)["id"] as String

        val timetableId = createTimetable("나의 시간표")
        val apply =
            put(
                "/v2/timetables/$timetableId/theme",
                """{"themeId":"$themeId"}""",
            )
        assertEquals(200, apply.statusCode.value())
        assertEquals(themeId, asMap(apply)["themeId"])

        // 내장 테마로 전환 (theme는 int 값, v1과 동일)
        val basic =
            put(
                "/v2/timetables/$timetableId/theme",
                """{"theme":1}""",
            )
        assertEquals(200, basic.statusCode.value())
        assertEquals(null, asMap(basic)["themeId"])
        assertEquals(1, asMap(basic)["theme"])
    }

    @Test
    fun `북마크 추가 조회 삭제`() {
        val add = post("/v2/bookmarks/lecture", """{"lectureId":"${lectureIds[0]}"}""")
        assertEquals(200, add.statusCode.value())

        val getBookmarks = get("/v2/bookmarks?year=2026&semester=3")
        assertEquals(1, (asMap(getBookmarks)["lectures"] as List<*>).size)

        val state = get("/v2/bookmarks/lectures/${lectureIds[0]}/state")
        assertEquals(true, state.body)

        val remove = deleteWithBody("/v2/bookmarks/lecture", """{"lectureId":"${lectureIds[0]}"}""")
        assertEquals(200, remove.statusCode.value())
        val afterRemove = get("/v2/bookmarks?year=2026&semester=3")
        assertEquals(0, (asMap(afterRemove)["lectures"] as List<*>).size)
    }

    private fun createTimetable(title: String): String {
        val add = post("/v2/timetables", """{"year":2026,"semester":3,"title":"$title"}""")
        return asList(add)[0]["id"] as String
    }
}
