package com.wafflestudio.snutt.api.evaluation

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseAggregateUpdater
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvaluationIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("evaluation_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired
    lateinit var evaluationRepository: EvaluationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var courseAggregateUpdater: CourseAggregateUpdater

    @LocalServerPort
    var port = 0

    private lateinit var verifiedToken: String
    private lateinit var unverifiedToken: String
    private lateinit var secondVerifiedToken: String
    private var lectureId: Long = 0L
    private var cursorLectureId: Long = 0L

    @BeforeAll
    fun seedDatabase() {
        val course =
            courseRepository.save(
                Course(
                    courseNumber = "M1522.004700",
                    instructor = "Chenglin Fan",
                    title = "계산이론연구 (Theoretical Foundation of AI)",
                    department = "컴퓨터공학부",
                    classification = "전선",
                ),
            )
        val lecture =
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
                    courseId = course.id,
                ),
                listOf(
                    ClassPlaceAndTime(DayOfWeek.MONDAY, "302-107", 930, 1005),
                    ClassPlaceAndTime(DayOfWeek.WEDNESDAY, "302-107", 930, 1005),
                ),
            )
        lectureId = lecture.id!!
        val cursorCourse =
            courseRepository.save(
                Course(
                    courseNumber = "2114.408A",
                    instructor = "임하진",
                    title = "HCI이론 및 실습",
                    department = "언론정보학과(연합전공 정보문화학)",
                    classification = "전필",
                ),
            )
        cursorLectureId =
            lectureRepository
                .save(
                    Lecture(
                        year = 2026,
                        semester = Semester.AUTUMN,
                        courseNumber = "2114.408A",
                        lectureNumber = "001",
                        courseTitle = "HCI이론 및 실습",
                        instructor = "임하진",
                        department = "언론정보학과(연합전공 정보문화학)",
                        academicYear = "4학년",
                        classification = "전필",
                        credit = 3,
                        quota = 25,
                        courseId = cursorCourse.id,
                    ),
                ).id!!

        verifiedToken = register("evaluser1", "eval1@snu.ac.kr")
        unverifiedToken = register("evaluser2", "eval2@snu.ac.kr")
        secondVerifiedToken = register("evaluser3", "eval3@snu.ac.kr")

        setEmailVerified("evaluser1")
        setEmailVerified("evaluser3")
    }

    private fun register(
        localId: String,
        email: String,
    ): String {
        val response =
            post(
                "/v2/auth/register",
                """{"localId":"$localId","password":"password1","email":"$email"}""",
            )
        assertEquals(200, response.statusCode.value())
        return body(response)["accessToken"].asString()
    }

    private fun setEmailVerified(localId: String) {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: error("user not found")
        user.isEmailVerified = true
        userRepository.save(user)
    }

    @BeforeEach
    fun cleanEvaluations() {
        evaluationRepository.deleteAll()
        courseRepository.saveAll(
            courseRepository.findAll().map { course ->
                course.apply {
                    evalCount = 0
                    avgRating = null
                }
            },
        )
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
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().post().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(String::class.java)
    }

    private fun get(
        uri: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().get().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(String::class.java)
    }

    private fun patch(
        uri: String,
        body: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().patch().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(String::class.java)
    }

    private fun delete(
        uri: String,
        token: String? = null,
    ): ResponseEntity<String> {
        val spec = client().delete().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(String::class.java)
    }

    private val jsonMapper = JsonMapper.builder().build()

    private fun body(response: ResponseEntity<String>): JsonNode = jsonMapper.readTree(response.body!!)

    private fun evalBody(
        content: String = "좋은 강의입니다",
        rating: Double = 4.5,
    ) = """{"content":"$content","gradeSatisfaction":4.0,"teachingSkill":4.5,"gains":3.5,"lifeBalance":4.0,"rating":$rating}"""

    @Test
    fun `이메일 미인증 사용자는 강의평을 쓸 수 없다`() {
        val response = post("/v2/lectures/$lectureId/evaluations", evalBody(), unverifiedToken)
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `강의평 생성과 course 집계 갱신`() {
        val response = post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 4.5), verifiedToken)
        assertEquals(200, response.statusCode.value())
        assertEquals(4.5, body(response)["rating"].asDouble())

        val course = courseRepository.findAll().first { it.courseNumber == "M1522.004700" }
        assertEquals(1, course.evalCount)
        assertEquals(4.5, course.avgRating)
    }

    @Test
    fun `중복 강의평은 거부된다`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        val duplicate = post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        assertEquals(409, duplicate.statusCode.value())
    }

    @Test
    fun `공감 추가와 취소`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        val list = get("/v2/lectures/$lectureId/evaluations", secondVerifiedToken)
        val evaluationId = body(list)["content"][0]["id"].asInt()

        val like = post("/v2/evaluations/$evaluationId/like", """{}""", secondVerifiedToken)
        assertEquals(200, like.statusCode.value())
        val duplicateLike = post("/v2/evaluations/$evaluationId/like", """{}""", secondVerifiedToken)
        assertEquals(409, duplicateLike.statusCode.value())

        val detail = get("/v2/evaluations/$evaluationId", secondVerifiedToken)
        assertEquals(1, body(detail)["likeCount"].asInt())
        assertEquals(true, body(detail)["isLiked"].asBoolean())

        val cancel = delete("/v2/evaluations/$evaluationId/like", secondVerifiedToken)
        assertEquals(200, cancel.statusCode.value())
        val afterCancel = get("/v2/evaluations/$evaluationId", secondVerifiedToken)
        assertEquals(0, body(afterCancel)["likeCount"].asInt())
    }

    @Test
    fun `수정 시 평점이 재계산되고 공감이 초기화된다`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 3.0), verifiedToken)
        post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 5.0), secondVerifiedToken)

        val list = get("/v2/lectures/$lectureId/evaluations", verifiedToken)
        val otherEvaluation = body(list)["content"][0]
        val otherId = otherEvaluation["id"].asInt()
        post("/v2/evaluations/$otherId/like", """{}""", verifiedToken)

        val update =
            patch(
                "/v2/evaluations/$otherId",
                """{"rating":1.0}""",
                secondVerifiedToken,
            )
        assertEquals(200, update.statusCode.value())

        val course = courseRepository.findAll().first { it.courseNumber == "M1522.004700" }
        assertEquals(2, course.evalCount)
        assertEquals(2.0, course.avgRating)
        assertEquals(0, body(get("/v2/evaluations/$otherId", verifiedToken))["likeCount"].asInt())
    }

    @Test
    fun `신고는 내 강의평이 아니어야 하고 중복 신고는 거부된다`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        val list = get("/v2/lectures/$lectureId/evaluations", secondVerifiedToken)
        val evaluationId = body(list)["content"][0]["id"].asInt()

        val selfReport = post("/v2/evaluations/$evaluationId/report", """{"content":"신고"}""", verifiedToken)
        assertEquals(409, selfReport.statusCode.value())

        val report = post("/v2/evaluations/$evaluationId/report", """{"content":"신고"}""", secondVerifiedToken)
        assertEquals(200, report.statusCode.value())
        val duplicateReport = post("/v2/evaluations/$evaluationId/report", """{"content":"신고"}""", secondVerifiedToken)
        assertEquals(409, duplicateReport.statusCode.value())
    }

    @Test
    fun `삭제는 숨김 처리하고 집계에서 빠진다`() {
        val create = post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 2.0), verifiedToken)
        val evaluationId = body(create)["id"].asInt()

        val delete = delete("/v2/evaluations/$evaluationId", verifiedToken)
        assertEquals(200, delete.statusCode.value())

        val course = courseRepository.findAll().first { it.courseNumber == "M1522.004700" }
        assertEquals(0, course.evalCount)
        assertEquals(null, course.avgRating)
    }

    @Test
    fun `강의평 목록은 커서로 페이지네이션된다`() {
        val users =
            (1..22).map { i ->
                userRepository.save(
                    User(
                        email = "evalbulk$i@snu.ac.kr",
                        isEmailVerified = true,
                        nickname = "evalbulk$i",
                        localId = "evalbulk$i",
                    ),
                )
            }
        val cursorCourse = courseRepository.findAll().first { it.courseNumber == "2114.408A" }
        users.forEachIndexed { i, user ->
            evaluationRepository.save(
                Evaluation(
                    courseId = cursorCourse.id!!,
                    userId = user.id,
                    year = 2026,
                    semester = Semester.AUTUMN,
                    content = "커서강의평${i + 1}",
                    rating = 4.0,
                ),
            )
        }

        val page1 = get("/v2/lectures/$cursorLectureId/evaluations", verifiedToken)
        val page1Node = body(page1)
        assertEquals(20, page1Node["content"].size())
        assertEquals(false, page1Node["last"].asBoolean())
        val cursor = page1Node["cursor"].asString()

        val page2 = get("/v2/lectures/$cursorLectureId/evaluations?cursor=$cursor", verifiedToken)
        val page2Node = body(page2)
        assertEquals(2, page2Node["content"].size())
        assertEquals(true, page2Node["last"].asBoolean())
    }

    @Test
    fun `평점 집계 property 여러 강의평의 평균과 일치한다`() {
        val ratings = List(7) { 1.0 + it * 0.5 }
        val users =
            ratings.mapIndexed { i, _ ->
                userRepository.save(
                    User(
                        email = "evalprop$i@snu.ac.kr",
                        isEmailVerified = true,
                        nickname = "evalprop$i",
                        localId = "evalprop$i",
                    ),
                )
            }
        val course =
            courseRepository.save(
                Course(
                    courseNumber = "F31.113",
                    instructor = "안명숙",
                    title = "경영학을 위한 수학",
                    department = "수리과학부",
                    classification = "교양",
                ),
            )
        val lecture =
            lectureRepository.save(
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
                    courseId = course.id,
                ),
            )
        users.zip(ratings).forEach { (user, rating) ->
            evaluationRepository.save(
                Evaluation(
                    courseId = course.id!!,
                    userId = user.id,
                    year = 2026,
                    semester = Semester.AUTUMN,
                    content = "프로퍼티강의평",
                    rating = rating,
                ),
            )
        }
        courseAggregateUpdater.update(course.id!!)
        val updated = courseRepository.findById(course.id!!).get()
        assertEquals(7, updated.evalCount)
        assertEquals(ratings.average(), updated.avgRating)
    }

    @Test
    fun `태그별 강의평 조회가 해당 과목의 집계 조건에 부합하는 evaluation만 반환한다`() {
        val user = userRepository.findByLocalIdAndActiveTrue("evaluser1")!!
        val course1 =
            courseRepository.save(
                Course(
                    courseNumber = "TAG.101",
                    instructor = "교수A",
                    title = "명강과목",
                    classification = "전선",
                ),
            )
        val course2 =
            courseRepository.save(
                Course(
                    courseNumber = "TAG.102",
                    instructor = "교수B",
                    title = "보통과목",
                    classification = "교양",
                ),
            )
        // course1: high teachingSkill & gains (WELL_TAUGHT & RECOMMENDED)
        evaluationRepository.save(
            Evaluation(
                courseId = course1.id!!,
                userId = user.id,
                year = 2026,
                semester = Semester.AUTUMN,
                content = "최고의 명강",
                gradeSatisfaction = 5.0,
                teachingSkill = 5.0,
                gains = 5.0,
                lifeBalance = 4.0,
                rating = 5.0,
            ),
        )
        // course2: low rating & low lifeBalance
        evaluationRepository.save(
            Evaluation(
                courseId = course2.id!!,
                userId = user.id,
                year = 2026,
                semester = Semester.AUTUMN,
                content = "힘든 교양",
                gradeSatisfaction = 2.0,
                teachingSkill = 2.0,
                gains = 2.0,
                lifeBalance = 1.0,
                rating = 2.0,
            ),
        )
        val wellTaught = get("/v2/evaluations/tags/well-taught", verifiedToken)
        assertEquals(200, wellTaught.statusCode.value())
        val wellTaughtItems = body(wellTaught)["content"]
        assertEquals(1, wellTaughtItems.size())
        assertEquals("최고의 명강", wellTaughtItems[0]["content"].asString())

        val liberalEducation = get("/v2/evaluations/tags/liberal-education", verifiedToken)
        assertEquals(200, liberalEducation.statusCode.value())
        val liberalItems = body(liberalEducation)["content"]
        assertEquals(1, liberalItems.size())
        assertEquals("힘든 교양", liberalItems[0]["content"].asString())
    }

    @Test
    fun `likeCount는 0 미만으로 감소하지 않는다`() {
        val create = post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        val evalId = body(create)["id"].asLong()
        evaluationRepository.decrementLikeCount(evalId)
        val eval = evaluationRepository.findById(evalId).get()
        assertEquals(0L, eval.likeCount)
    }
}
