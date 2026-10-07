package com.wafflestudio.snutt.api.coverage

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.legacyApiKey
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.device.repository.UserDeviceRepository
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.v1compat.auth.LegacyTokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity

class CoverageGapIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var themeRepository: TimetableThemeRepository

    @Autowired
    lateinit var publishedThemeRepository: PublishedThemeRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var userDeviceRepository: UserDeviceRepository

    @Autowired
    lateinit var friendRepository: FriendRepository

    @Autowired
    lateinit var legacyTokenService: LegacyTokenService

    private lateinit var userAToken: String
    private lateinit var userBToken: String
    private lateinit var userA: User
    private lateinit var userB: User
    private var lectureId: Long = 0L

    @BeforeEach
    fun seed() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.SPRING))
        coursebookRepository.save(Coursebook(year = 2025, semester = Semester.AUTUMN))

        val course = courseRepository.save(Course(courseNumber = "M2174.001600", instructor = "박지수", title = "생활과학신입생세미나"))
        lectureId =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.SPRING,
                    courseNumber = "M2174.001600",
                    lectureNumber = "001",
                    courseTitle = "생활과학신입생세미나",
                    instructor = "박지수",
                    department = "생활과학대학",
                    academicYear = "1학년",
                    classification = "전필",
                    credit = 1,
                    quota = 150,
                    courseId = course.id,
                ),
                listOf(ClassPlaceAndTime(DayOfWeek.TUESDAY, "222-701", 1020, 1070)),
            ).id!!

        userAToken = register("coverusera")
        userBToken = register("coveruserb")
        userA = userRepository.findByLocalIdAndActiveTrue("coverusera")!!
        userB = userRepository.findByLocalIdAndActiveTrue("coveruserb")!!
    }

    private fun registerDevice(
        registrationId: String,
        deviceId: String,
        token: String,
    ): ResponseEntity<String> =
        client()
            .post()
            .uri("/v2/users/me/devices/$registrationId")
            .headers { it.setBearerAuth(token) }
            .header("x-device-id", deviceId)
            .retrieve()
            .toEntity(String::class.java)

    private fun activeDeviceCount(deviceId: String): Int =
        userDeviceRepository.findAllByUserIdInAndIsDeletedFalse(listOf(userA.id!!)).count { it.deviceId == deviceId }

    @Test
    fun `기기 등록과 해제가 FCM 토픽 구독까지 반영한다`() {
        val registered = registerDevice("fcm-token-abc", "device-1", userAToken)
        assertEquals(200, registered.statusCode.value())
        verify(pushClient).subscribeGlobalTopic("fcm-token-abc")

        registerDevice("fcm-token-def", "device-1", userAToken)
        assertEquals(1, activeDeviceCount("device-1"))

        val removed = delete("/v2/users/me/devices/fcm-token-def", userAToken)
        assertEquals(200, removed.statusCode.value())
        assertEquals(0, activeDeviceCount("device-1"))
        verify(pushClient).unsubscribeGlobalTopic("fcm-token-def")
    }

    @Test
    fun `구 경로로도 기기를 등록한다`() {
        val legacyToken = legacyTokenService.issue(userA)
        val response =
            client()
                .post()
                .uri("/v1/user/device/legacy-fcm-token")
                .header("x-access-apikey", legacyApiKey())
                .header("x-access-token", legacyToken)
                .header("x-device-id", "legacy-device")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, response.statusCode.value())
        verify(pushClient).subscribeGlobalTopic("legacy-fcm-token")
    }

    @Test
    fun `학기 상태는 현재와 다음 학기를 알려준다`() {
        val response = get("/v2/semesters/status")
        assertEquals(200, response.statusCode.value())
        val next = body(response)["next"]
        assertTrue(next.hasNonNull("year"))
        assertTrue(next.hasNonNull("semester"))
    }

    @Test
    fun `친구가 공유한 테마를 조회한다`() {
        friendRepository.save(Friend(fromUserId = userA.id!!, toUserId = userB.id!!, isAccepted = true))
        val theme =
            themeRepository.save(
                TimetableTheme(
                    userId = userB.id!!,
                    name = "친구테마",
                    colors = listOf(ColorSet(backgroundColor = "#111111", foregroundColor = "#222222")),
                ),
            )
        publishedThemeRepository.save(
            PublishedTheme(
                authorId = userB.id!!,
                sourceThemeId = theme.id!!,
                name = "친구가공유한테마",
                colors = checkNotNull(theme.colors),
                downloadCount = 7,
            ),
        )

        val response = get("/v2/theme-publications/friends", userAToken)
        assertEquals(200, response.statusCode.value())
        val themes = body(response)["content"]
        assertEquals(1, themes.size())
        assertEquals("친구가공유한테마", themes[0]["name"].asString())
    }

    @Test
    fun `커스텀 테마를 기본 테마로 지정하고 해제한다`() {
        val created =
            post(
                "/v2/themes",
                """{"name":"기본테마후보","colors":[{"backgroundColor":"#000000","foregroundColor":"#ffffff"}]}""",
                userBToken,
            )
        assertEquals(200, created.statusCode.value())
        val themeId = body(created)["id"].asLong()

        val setDefault = post("/v2/themes/$themeId/default", token = userBToken)
        assertEquals(200, setDefault.statusCode.value())
        assertEquals(true, body(setDefault)["isDefault"].asBoolean())

        val marked = body(get("/v2/themes", userBToken)).filter { it["isDefault"].asBoolean() }
        assertEquals(1, marked.size)
        assertEquals(themeId, marked[0]["id"].asLong())

        val unset = delete("/v2/themes/$themeId/default", userBToken)
        assertEquals(200, unset.statusCode.value())
        assertEquals("SNUTT", body(unset)["name"].asString())
    }

    @Test
    fun `기본 테마로 지정된 커스텀 테마를 삭제하면 내장 테마로 돌아간다`() {
        val created =
            post(
                "/v2/themes",
                """{"name":"삭제할기본테마","colors":[{"backgroundColor":"#000000","foregroundColor":"#ffffff"}]}""",
                userBToken,
            )
        assertEquals(200, created.statusCode.value())
        val themeId = body(created)["id"].asLong()
        post("/v2/themes/$themeId/default", token = userBToken)

        val deleted = delete("/v2/themes/$themeId", userBToken)
        assertEquals(200, deleted.statusCode.value())

        val marked = body(get("/v2/themes", userBToken)).filter { it["isDefault"].asBoolean() }
        assertEquals(1, marked.size)
        assertEquals("SNUTT", marked[0]["name"].asString())
    }

    @Test
    fun `최근 수강 강의를 강의평 작성 대상으로 돌려준다`() {
        val table = post("/v2/timetables", """{"year":2026,"semester":1,"title":"수강내역"}""", userAToken)
        assertEquals(200, table.statusCode.value())
        val tableId = body(table).first { it["title"].asString() == "수강내역" }["id"].asLong()
        val added = post("/v2/timetables/$tableId/lectures", """{"lectureId":$lectureId}""", userAToken)
        assertEquals(200, added.statusCode.value())

        val response = get("/v2/users/me/lectures/latest", userAToken)
        assertEquals(200, response.statusCode.value())
        val lectures = body(response)
        assertEquals(1, lectures.size())
        assertEquals("생활과학신입생세미나", lectures[0]["title"].asString())
        assertEquals(2026, lectures[0]["takenYear"].asInt())
    }
}
