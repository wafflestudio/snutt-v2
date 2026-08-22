package com.wafflestudio.snutt.api.coverage

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
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.popup.repository.PopupRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.ResponseEntity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V1CompatPathIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("v1compat_path_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }

        @JvmStatic
        @DynamicPropertySource
        fun storageProperties(registry: DynamicPropertyRegistry) {
            registry.add("snutt.storage.namespace") { "testnamespace" }
        }
    }

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var coursebookRepository: CoursebookRepository

    @Autowired lateinit var popupRepository: PopupRepository

    @Autowired lateinit var lectureRepository: LectureRepository

    @Autowired lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @LocalServerPort var port = 0

    private lateinit var legacyToken: String

    @BeforeAll
    fun seed() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))
        saveLectureWithTimes(
            lectureRepository,
            lectureClassTimeRepository,
            Lecture(
                year = 2026,
                semester = Semester.AUTUMN,
                courseNumber = "M1522.004700",
                lectureNumber = "001",
                courseTitle = "계산이론연구 (Theoretical Foundation of AI)",
                instructor = "Chenglin Fan",
                department = "컴퓨터공학부",
                academicYear = "석박사통합",
                classification = "전선",
                credit = 3,
                quota = 10,
                remark = "Ⓔ®강의 교수의 지도학생만 수강신청 가능",
                courseTitleEn = "Studies in Theory of Computation (Theoretical Foundation of AI)",
                departmentEn = "Department of Computer Science and Engineering",
                academicYearEn = "Combined Masters/Doctorate",
                classificationEn = "Elective Subject for Major",
            ),
            listOf(
                ClassPlaceAndTime(DayOfWeek.MONDAY, "302-107", 930, 1005),
                ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "302-107", 930, 1005),
            ),
        )
        popupRepository.save(
            Popup(popupKey = "notice", imageOriginUri = "s3://snutt-asset/popup-images/a.jpg", hiddenDays = 7),
        )
        val register =
            client()
                .post()
                .uri("/v1/auth/register_local")
                .body("""{"id":"v1pathuser","password":"password1","email":"v1path@snu.ac.kr"}""")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, register.statusCode.value())
        legacyToken = body(register)["token"].asString()
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

    private fun getV1(uri: String): ResponseEntity<String> =
        client()
            .get()
            .uri(uri)
            .header("x-access-token", legacyToken)
            .retrieve()
            .toEntity(String::class.java)

    private fun postV1(
        uri: String,
        body: String? = null,
    ): ResponseEntity<String> {
        val spec = client().post().uri(uri).header("x-access-token", legacyToken)
        body?.let { spec.body(it) }
        return spec.retrieve().toEntity(String::class.java)
    }

    private val jsonMapper = JsonMapper.builder().build()

    private fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

    @Test
    fun `계정 경로는 단수형 v1 user 이다`() {
        val info = getV1("/v1/user/info")
        assertEquals(200, info.statusCode.value())
        assertEquals("v1pathuser", body(info)["local_id"].asString())

        val verification = getV1("/v1/user/email/verification")
        assertEquals(200, verification.statusCode.value())
        assertEquals(false, body(verification)["isEmailVerified"].asBoolean())
    }

    @Test
    fun `내 정보는 복수형 v1 users me 이다`() {
        val me = getV1("/v1/users/me")
        assertEquals(200, me.statusCode.value())
        assertEquals("v1pathuser", body(me)["localId"].asString())

        val providers = getV1("/v1/users/me/social_providers")
        assertEquals(200, providers.statusCode.value())
        assertEquals(true, body(providers)["local"].asBoolean())
        assertEquals(false, body(providers)["facebook"].asBoolean())
    }

    @Test
    fun `친구 목록은 content와 totalCount로 감싼다`() {
        val response = getV1("/v1/friends?state=ACTIVE")
        assertEquals(200, response.statusCode.value())
        val node = body(response)
        assertTrue(node.hasNonNull("content"))
        assertEquals(0, node["totalCount"].asInt())
    }

    @Test
    fun `테마 목록은 colors 필드를 쓴다`() {
        val response = getV1("/v1/themes")
        assertEquals(200, response.statusCode.value())
        val themes = body(response)
        assertTrue(themes.size() > 0)
        assertTrue(themes[0].has("theme"))
        assertTrue(themes[0].has("isDefault"))
        assertTrue(themes[0].has("isCustom"))
    }

    @Test
    fun `테마 검색은 POST 본문으로 받는다`() {
        val response = postV1("/v1/themes/search", """{"keyword":"없는테마"}""")
        assertEquals(200, response.statusCode.value())
        assertTrue(body(response).hasNonNull("content"))
    }

    @Test
    fun `태그 갱신 시각 경로가 살아있다`() {
        val response = getV1("/v1/tags/2026/3/update_time")
        assertEquals(200, response.statusCode.value())
        assertTrue(body(response).hasNonNull("updated_at"))
    }

    @Test
    fun `알림 경로는 단수형이다`() {
        val list = getV1("/v1/notification")
        assertEquals(200, list.statusCode.value())
        val count = getV1("/v1/notification/count")
        assertEquals(200, count.statusCode.value())
        assertTrue(body(count).hasNonNull("count"))
    }

    @Test
    fun `팝업은 공개 오브젝트 URL과 구 필드명을 함께 준다`() {
        val response = getV1("/v1/popups")
        assertEquals(200, response.statusCode.value())
        // 레거시 계약: ListResponse(content, totalCount) 래퍼로 감싸 반환된다
        val popups = body(response)["content"]
        assertEquals(1, popups.size())
        assertEquals(1, body(response)["totalCount"].asInt())
        assertEquals("notice", popups[0]["key"].asString())
        assertEquals(
            "https://objectstorage.ap-chuncheon-1.oraclecloud.com/n/testnamespace/b/snutt-asset/o/popup-images/a.jpg",
            popups[0]["imageUri"].asString(),
        )
        assertEquals(popups[0]["imageUri"], popups[0]["image_url"])
        assertEquals(7, popups[0]["hidden_days"].asInt())
    }

    @Test
    fun `관리자 이미지 업로드 URI를 발급한다`() {
        userRepository.findByLocalIdAndActiveTrue("v1pathuser")!!.let {
            it.isAdmin = true
            userRepository.save(it)
        }
        val response =
            client()
                .post()
                .uri("/v1/admin/images/popup/upload-uris?count=2")
                .header("x-access-token", legacyToken)
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, response.statusCode.value())
        val uris = body(response)
        assertEquals(2, uris.size())
        assertTrue(uris[0]["fileOriginUri"].asString().startsWith("s3://snutt-asset/popup-images/"))
        assertTrue(uris[0]["fileUri"].asString().startsWith("https://objectstorage."))
    }

    @Test
    fun `학기 상태와 강의평 요약은 인증 없이 열려 있다`() {
        val status =
            client()
                .get()
                .uri("/v1/semesters/status")
                .retrieve()
                .toEntity(String::class.java)
        assertEquals(200, status.statusCode.value())
    }

    @Test
    fun `구 강의평 경로가 살아있다`() {
        assertEquals(403, getV1("/v1/ev-service/v1/evaluations/users/me").statusCode.value())

        userRepository.findByLocalIdAndActiveTrue("v1pathuser")!!.let {
            it.isEmailVerified = true
            userRepository.save(it)
        }
        val mine = getV1("/v1/ev-service/v1/evaluations/users/me")
        assertEquals(200, mine.statusCode.value())
        val search = getV1("/v1/ev-service/v1/lectures?query=&page=0")
        assertEquals(200, search.statusCode.value())
        assertTrue(body(search).hasNonNull("content"))
    }
}
