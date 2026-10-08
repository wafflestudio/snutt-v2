package com.wafflestudio.snutt.api.misc

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.storage.FileUploadUri
import com.wafflestudio.snutt.core.common.storage.StorageSource
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired

class MiscDomainIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired
    lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var userRepository: UserRepository

    private lateinit var userAToken: String
    private lateinit var userBToken: String
    private lateinit var adminToken: String
    private var lectureId: Long = 0L

    @BeforeEach
    fun seed() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        lectureId =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = Semester.AUTUMN,
                    courseNumber = "E43.101",
                    lectureNumber = "001",
                    courseTitle = "건강과 삶",
                    instructor = "김부석",
                    department = "체육교육과",
                    academicYear = "1학년",
                    category = "예술과 체육",
                    categoryPre2025 = "체육",
                    classification = "교양",
                    credit = 1,
                    quota = 30,
                ),
                listOf(ClassPlaceAndTime(DayOfWeek.THURSDAY, "71-1-214", 540, 590)),
            ).id!!

        userAToken = register("miscuserA")
        userBToken = register("miscuserB")
        adminToken = register("miscadmin")
        userRepository.findByLocalIdAndActiveTrue("miscadmin")!!.let { user ->
            user.isAdmin = true
            userRepository.save(user)
        }
    }

    @Test
    fun `친구 요청 수락과 표시 이름`() {
        val request = post("/v2/friends", nicknameRequest("miscuserB"), userAToken)
        assertEquals(200, request.statusCode.value())

        val requested = body(get("/v2/friends?state=REQUESTED", userBToken))
        assertEquals(1, requested.size())
        val friendId = requested[0]["id"].asString()

        val duplicate = post("/v2/friends", nicknameRequest("miscuserB"), userAToken)
        assertEquals(409, duplicate.statusCode.value())

        assertEquals(200, post("/v2/friends/$friendId/accept", """{}""", userBToken).statusCode.value())

        val active = body(get("/v2/friends?state=ACTIVE", userAToken))
        assertEquals(1, active.size())
        assertFalse(active[0].hasNonNull("displayName"))

        assertEquals(200, patch("/v2/friends/$friendId/display-name", """{"displayName":"단짝"}""", userAToken).statusCode.value())
        val after = body(get("/v2/friends?state=ACTIVE", userAToken))
        assertEquals("단짝", after[0]["displayName"].asString())

        assertEquals(200, delete("/v2/friends/$friendId", userAToken).statusCode.value())
        assertEquals(0, body(get("/v2/friends?state=ACTIVE", userAToken)).size())
    }

    @Test
    fun `친구 초대 링크로 친구가 된다`() {
        val link = body(get("/v2/friends/generate-link", userAToken))
        val requestToken = link["requestToken"].asString()

        val accept = post("/v2/friends/accept-link/$requestToken", """{}""", userBToken)
        assertEquals(200, accept.statusCode.value())

        val duplicate = post("/v2/friends/accept-link/$requestToken", """{}""", userBToken)
        assertEquals(409, duplicate.statusCode.value())

        val invalid = post("/v2/friends/accept-link/invalidtoken", """{}""", userBToken)
        assertEquals(404, invalid.statusCode.value())
    }

    @Test
    fun `빈자리 알림 등록 조회 삭제`() {
        val add = post("/v2/vacancy-notifications/lectures/$lectureId", """{}""", userAToken)
        assertEquals(200, add.statusCode.value())

        val state = get("/v2/vacancy-notifications/lectures/$lectureId/state", userAToken)
        assertEquals(true, body(state).asBoolean())

        val lectures = body(get("/v2/vacancy-notifications/lectures", userAToken))
        val lectureList = lectures["lectures"]
        assertEquals(1, lectureList.size())
        assertEquals("건강과 삶", lectureList[0]["courseTitle"].asString())

        val remove = delete("/v2/vacancy-notifications/lectures/$lectureId", userAToken)
        assertEquals(200, remove.statusCode.value())
        assertEquals(0, body(get("/v2/vacancy-notifications/lectures", userAToken))["lectures"].size())
    }

    @Test
    fun `관리자 이미지 업로드 URI를 발급한다`() {
        val issued =
            listOf(
                FileUploadUri("https://upload/1", "s3://snutt-asset/popup-images/1.jpg", "https://cdn/1.jpg"),
                FileUploadUri("https://upload/2", "s3://snutt-asset/popup-images/2.jpg", "https://cdn/2.jpg"),
            )
        whenever(uploadUriIssuer.issue(StorageSource.POPUP, 2)).thenReturn(issued)

        val response = post("/v2/admin/images/popup/upload-uris?count=2", "", adminToken)
        assertEquals(200, response.statusCode.value())
        val uris = body(response)
        assertEquals(2, uris.size())
        assertEquals("s3://snutt-asset/popup-images/1.jpg", uris[0]["fileOriginUri"].asString())
        assertEquals("https://cdn/1.jpg", uris[0]["fileUri"].asString())
        verify(uploadUriIssuer).issue(StorageSource.POPUP, 2)

        val forbidden = post("/v2/admin/images/popup/upload-uris?count=2", "", userAToken)
        assertEquals(403, forbidden.statusCode.value())
    }

    @Test
    fun `알림함 조회와 읽음 처리`() {
        val broadcast =
            post(
                "/v2/admin/notifications",
                """{"title":"전체공지","message":"안녕하세요","type":0}""",
                adminToken,
            )
        assertEquals(200, broadcast.statusCode.value())

        val notifications = body(get("/v2/notifications", userAToken))["content"]
        assertEquals(1, notifications.size())
        assertEquals("전체공지", notifications[0]["title"].asString())

        val count = body(get("/v2/notifications/count", userAToken))
        assertEquals(1, count["count"].asInt())

        body(get("/v2/notifications?explicit=1", userAToken))
        val after = body(get("/v2/notifications/count", userAToken))
        assertEquals(0, after["count"].asInt())
    }

    @Test
    fun `팝업과 클라이언트 설정`() {
        val popup =
            post(
                "/v2/admin/popups",
                """{"popupKey":"welcome2026","imageOriginUri":"https://cdn.example.com/welcome.png"}""",
                adminToken,
            )
        assertEquals(200, popup.statusCode.value())

        val popups = body(get("/v2/popups"))
        assertEquals(1, popups.size())
        assertEquals("https://cdn.example.com/welcome.png", popups[0]["imageUri"].asString())

        val config =
            post(
                "/v2/admin/configs/notice",
                """{"value":{"text":"공지"},"osType":"ios","minVersion":"3.0.0","maxVersion":"4.0.0"}""",
                adminToken,
            )
        assertEquals(200, config.statusCode.value())

        val adapted =
            client()
                .get()
                .uri("/v2/configs")
                .header("x-os-type", "ios")
                .header("x-client-key", "test-ios-key")
                .header("x-app-version", "3.5.0")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, adapted.statusCode.value())
        val configs = body(adapted)
        assertTrue(configs.has("notice"))

        val outOfRange =
            client()
                .get()
                .uri("/v2/configs")
                .header("x-os-type", "ios")
                .header("x-client-key", "test-ios-key")
                .header("x-app-version", "5.0.0")
                .retrieve()
                .toEntity(String::class.java)
        val outOfRangeNode = body(outOfRange)
        assertTrue(outOfRangeNode.isObject)
        assertEquals(0, outOfRangeNode.size())
    }

    @Test
    fun `푸시 프리퍼런스 저장과 조회`() {
        val saved =
            post(
                "/v2/users/me/push-preferences",
                """{"pushPreferences":[{"type":"LECTURE_UPDATE","isEnabled":false}]}""",
                userAToken,
            )
        assertEquals(200, saved.statusCode.value())
        val preferences = body(saved)["pushPreferences"]
        assertEquals(1, preferences.size())
        assertEquals("LECTURE_UPDATE", preferences[0]["type"].asString())
        assertEquals(false, preferences[0]["isEnabled"].asBoolean())
    }

    @Test
    fun `정적 페이지는 v2 정적 경로로 제공되고 구 루트 경로는 영구 리다이렉트된다`() {
        val member =
            client()
                .get()
                .uri("/v2/static/member")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, member.statusCode.value())
        assertTrue(member.body!!.contains("<html"))

        val legacy =
            client()
                .get()
                .uri("/member")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, legacy.statusCode.value())
        assertTrue(legacy.body!!.contains("<html"))
    }

    private fun nicknameRequest(localId: String): String {
        val user = userRepository.findByLocalIdAndActiveTrue(localId)!!
        return """{"nickname":"${user.nickname}","nicknameTag":"${user.nicknameTag}"}"""
    }
}
