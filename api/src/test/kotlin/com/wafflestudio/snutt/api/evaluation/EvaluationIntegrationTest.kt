package com.wafflestudio.snutt.api.evaluation

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
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

/**
 * M4 DoD 검증: 강의평 CRUD/공감/신고, 이메일 인증 게이트, 커서 페이지네이션,
 * course.avg_rating/eval_count 비정규화 재계산 (PLAN.md §7 M4)
 */
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

    @Autowired lateinit var lectureClassTimeRepository: com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository

    @Autowired
    lateinit var evaluationRepository: EvaluationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var courseAggregateUpdater: com.wafflestudio.snutt.core.domain.evaluation.service.CourseAggregateUpdater

    @LocalServerPort
    var port = 0

    private lateinit var verifiedToken: String
    private lateinit var unverifiedToken: String
    private lateinit var secondVerifiedToken: String
    private lateinit var lectureId: String
    private lateinit var cursorLectureId: String

    @BeforeAll
    fun seedDatabase() {
        // 강의평 대상 강의 (course 링크 필요)
        val course =
            courseRepository.save(
                com.wafflestudio.snutt.core.domain.evaluation.model.Course(
                    courseNumber = "4190.999",
                    instructor = "평가교수",
                    title = "강의평강의",
                    classification = "전선",
                ),
            )
        val lecture =
            saveLectureWithTimes(
                lectureRepository,
                lectureClassTimeRepository,
                Lecture(
                    year = 2026,
                    semester = com.wafflestudio.snutt.core.common.enums.Semester.AUTUMN,
                    courseNumber = "4190.999",
                    lectureNumber = "001",
                    courseTitle = "강의평강의",
                    instructor = "평가교수",
                    courseId = course.id,
                ),
                listOf(ClassPlaceAndTime(com.wafflestudio.snutt.core.common.enums.DayOfWeek.MONDAY, "302-101", 570, 660)),
            )
        lectureId = lecture.externalId
        cursorLectureId =
            lectureRepository
                .save(
                    Lecture(
                        year = 2026,
                        semester = com.wafflestudio.snutt.core.common.enums.Semester.AUTUMN,
                        courseNumber = "4190.998",
                        lectureNumber = "001",
                        courseTitle = "커서테스트강의",
                        instructor = "평가교수2",
                        courseId = course.id,
                    ),
                ).externalId

        verifiedToken = register("evaluser1", "eval1@snu.ac.kr")
        unverifiedToken = register("evaluser2", "eval2@snu.ac.kr")
        secondVerifiedToken = register("evaluser3", "eval3@snu.ac.kr")

        // 이메일 인증은 v2 인증 흐름 밖이므로 저장소에서 직접 검증 상태로 만든다
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
        return asMap(response)["accessToken"] as String
    }

    private fun setEmailVerified(localId: String) {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: error("user not found")
        user.isEmailVerified = true
        userRepository.save(user)
    }

    // 강의평은 테스트 간 공유되므로 각 테스트 전에 비우고 course 집계를 초기화한다
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

    @Suppress("UNCHECKED_CAST")
    private fun post(
        uri: String,
        body: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().post().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun get(
        uri: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().get().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun patch(
        uri: String,
        body: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().patch().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.body(body).retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun delete(
        uri: String,
        token: String? = null,
    ): ResponseEntity<Any> {
        val spec = client().delete().uri(uri)
        token?.let { spec.headers { h -> h.setBearerAuth(it) } }
        return spec.retrieve().toEntity(Any::class.java)
    }

    private fun asMap(response: ResponseEntity<Any>): Map<String, Any?> = response.body as Map<String, Any?>

    private fun evalBody(
        content: String = "좋은 강의입니다",
        rating: Double = 4.5,
    ) = """{"content":"$content","gradeSatisfaction":4.0,"teachingSkill":4.5,"gains":3.5,"lifeBalance":4.0,"rating":$rating}"""

    @Test
    fun `이메일 미인증 사용자는 강의평을 쓸 수 없다`() {
        val response = post("/v2/lectures/$lectureId/evaluations", evalBody(), unverifiedToken)
        assertEquals(403, response.statusCode.value())
        assertEquals(0x3011, asMap(response)["errcode"])
    }

    @Test
    fun `강의평 생성과 course 집계 갱신`() {
        val response = post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 4.5), verifiedToken)
        assertEquals(200, response.statusCode.value())
        assertEquals(4.5, asMap(response)["rating"])

        val course = courseRepository.findAll().first { it.courseNumber == "4190.999" }
        assertEquals(1, course.evalCount)
        assertEquals(4.5, course.avgRating)
    }

    @Test
    fun `중복 강의평은 거부된다`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        val duplicate = post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        assertEquals(409, duplicate.statusCode.value())
        assertEquals(40910, asMap(duplicate)["errcode"])
    }

    @Test
    fun `공감 추가와 취소`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        val list = get("/v2/lectures/$lectureId/evaluations", secondVerifiedToken)
        val evaluationId = ((asMap(list)["content"] as List<*>)[0] as Map<*, *>)["id"] as Int

        val like = post("/v2/evaluations/$evaluationId/like", """{}""", secondVerifiedToken)
        assertEquals(200, like.statusCode.value())
        val duplicateLike = post("/v2/evaluations/$evaluationId/like", """{}""", secondVerifiedToken)
        assertEquals(409, duplicateLike.statusCode.value())

        val detail = get("/v2/evaluations/$evaluationId", secondVerifiedToken)
        assertEquals(1, (asMap(detail)["likeCount"] as Int).toLong())
        assertEquals(true, asMap(detail)["isLiked"])

        val cancel = delete("/v2/evaluations/$evaluationId/like", secondVerifiedToken)
        assertEquals(200, cancel.statusCode.value())
        val afterCancel = get("/v2/evaluations/$evaluationId", secondVerifiedToken)
        assertEquals(0, (asMap(afterCancel)["likeCount"] as Int).toLong())
    }

    @Test
    fun `수정 시 평점이 재계산되고 공감이 초기화된다`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 3.0), verifiedToken)
        post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 5.0), secondVerifiedToken)

        val list = get("/v2/lectures/$lectureId/evaluations", verifiedToken)
        val otherEvaluation = (asMap(list)["content"] as List<*>)[0] as Map<*, *>
        val otherId = otherEvaluation["id"] as Int
        post("/v2/evaluations/$otherId/like", """{}""", verifiedToken)

        val update =
            patch(
                "/v2/evaluations/$otherId",
                """{"rating":1.0}""",
                secondVerifiedToken,
            )
        assertEquals(200, update.statusCode.value())

        val course = courseRepository.findAll().first { it.courseNumber == "4190.999" }
        assertEquals(2, course.evalCount)
        assertEquals(2.0, course.avgRating)
        assertEquals(0, (asMap(get("/v2/evaluations/$otherId", verifiedToken))["likeCount"] as Int).toLong())
    }

    @Test
    fun `신고는 내 강의평이 아니어야 하고 중복 신고는 거부된다`() {
        post("/v2/lectures/$lectureId/evaluations", evalBody(), verifiedToken)
        val list = get("/v2/lectures/$lectureId/evaluations", secondVerifiedToken)
        val evaluationId = ((asMap(list)["content"] as List<*>)[0] as Map<*, *>)["id"] as Int

        // 내 강의평 신고
        val selfReport = post("/v2/evaluations/$evaluationId/report", """{"content":"신고"}""", verifiedToken)
        assertEquals(409, selfReport.statusCode.value())
        assertEquals(40914, asMap(selfReport)["errcode"])

        val report = post("/v2/evaluations/$evaluationId/report", """{"content":"신고"}""", secondVerifiedToken)
        assertEquals(200, report.statusCode.value())
        val duplicateReport = post("/v2/evaluations/$evaluationId/report", """{"content":"신고"}""", secondVerifiedToken)
        assertEquals(409, duplicateReport.statusCode.value())
    }

    @Test
    fun `삭제는 숨김 처리하고 집계에서 빠진다`() {
        val create = post("/v2/lectures/$lectureId/evaluations", evalBody(rating = 2.0), verifiedToken)
        val evaluationId = asMap(create)["id"] as Int

        val delete = delete("/v2/evaluations/$evaluationId", verifiedToken)
        assertEquals(200, delete.statusCode.value())

        val course = courseRepository.findAll().first { it.courseNumber == "4190.999" }
        assertEquals(0, course.evalCount)
        assertEquals(null, course.avgRating)
    }

    @Test
    fun `강의평 목록은 커서로 페이지네이션된다`() {
        // userA가 보지 못하도록 다른 사용자 22명의 강의평을 직접 적재한다
        val users =
            (1..22).map { i ->
                userRepository.save(
                    User(
                        email = "evalbulk$i@snu.ac.kr",
                        isEmailVerified = true,
                        nickname = "evalbulk$i",
                        localId = "evalbulk$i",
                        credentialHash = "bulkcred$i",
                    ),
                )
            }
        val cursorCourse = courseRepository.findAll().first { it.courseNumber == "4190.999" }
        users.forEachIndexed { i, user ->
            evaluationRepository.save(
                Evaluation(
                    courseId = cursorCourse.id!!,
                    userId = user.id,
                    year = 2026,
                    semester = com.wafflestudio.snutt.core.common.enums.Semester.AUTUMN,
                    content = "커서강의평${i + 1}",
                    rating = 4.0,
                ),
            )
        }

        val page1 = get("/v2/lectures/$cursorLectureId/evaluations", verifiedToken)
        val page1Map = asMap(page1)
        assertEquals(20, (page1Map["content"] as List<*>).size)
        assertEquals(false, page1Map["last"])
        val cursor = page1Map["cursor"] as String

        val page2 = get("/v2/lectures/$cursorLectureId/evaluations?cursor=$cursor", verifiedToken)
        val page2Map = asMap(page2)
        assertEquals(2, (page2Map["content"] as List<*>).size)
        assertEquals(true, page2Map["last"])
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
                        credentialHash = "propcred$i",
                    ),
                )
            }
        val course = courseRepository.findAll().first { it.courseNumber == "4190.999" }
        val lecture =
            lectureRepository.save(
                Lecture(
                    year = 2026,
                    semester = com.wafflestudio.snutt.core.common.enums.Semester.AUTUMN,
                    courseNumber = "4190.997",
                    lectureNumber = "001",
                    courseTitle = "프로퍼티강의",
                    instructor = "평가교수3",
                    courseId = course.id,
                ),
            )
        users.zip(ratings).forEach { (user, rating) ->
            evaluationRepository.save(
                Evaluation(
                    courseId = course.id!!,
                    userId = user.id,
                    year = 2026,
                    semester = com.wafflestudio.snutt.core.common.enums.Semester.AUTUMN,
                    content = "프로퍼티강의평",
                    rating = rating,
                ),
            )
        }
        // 서비스 경로와 동일한 재계산을 호출하면 course 집계가 7건의 평균과 일치한다
        courseAggregateUpdater.update(course.id!!)
        val updated = courseRepository.findById(course.id!!).get()
        assertEquals(7, updated.evalCount)
        assertEquals(ratings.average(), updated.avgRating)
    }
}
