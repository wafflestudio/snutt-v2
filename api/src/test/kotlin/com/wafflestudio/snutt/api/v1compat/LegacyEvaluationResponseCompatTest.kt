package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode

/**
 * 강의평 웹이 읽는 v1compat EV 응답 중 v1 EV의 동작을 그대로 따라야 하는 것(parity B5b, B6).
 * - 빈 `cursor=`: v1 PageUtils.getCursor는 해석할 수 없는 커서를 null로 봐 첫 페이지를 줬다.
 * - 세부 점수가 NULL인 강의평: v1 조회 응답은 non-null 프로젝션이라 0.0을, 수정 응답은 엔티티라 필드 생략을 냈다.
 */
class LegacyEvaluationResponseCompatTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var courseRepository: CourseRepository

    @Autowired
    lateinit var evaluationRepository: EvaluationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    private val scoreKeys = listOf("grade_satisfaction", "teaching_skill", "gains", "life_balance")

    private lateinit var legacyToken: String
    private var courseId: Long = 0
    private var othersEvaluationId: Long = 0
    private var myEvaluationId: Long = 0

    @BeforeEach
    fun seed() {
        val (token, userId) = v1Register("evreader")
        legacyToken = token
        userRepository.findByLocalIdAndActiveTrue("evreader")!!.let {
            it.isEmailVerified = true
            userRepository.save(it)
        }
        val course = courseRepository.save(Course(courseNumber = "300.201", instructor = "김선형", title = "선형대수"))
        courseId = course.id!!
        // snuev에서 넘어온 강의평: 세부 점수 없이 rating만 있다. 과목 목록은 본인 강의평을 빼므로 다른 사람 것과 본인 것을 하나씩 둔다.
        othersEvaluationId = snuevEvaluation(fixtures.user("evwriter").id!!, "다른 사람이 snuev에 남긴 강의평입니다.")
        myEvaluationId = snuevEvaluation(userId.toLong(), "내가 snuev에 남긴 강의평입니다.")
    }

    private fun snuevEvaluation(
        authorId: Long,
        content: String,
    ): Long =
        evaluationRepository
            .save(
                Evaluation(
                    courseId = courseId,
                    userId = authorId,
                    year = 2019,
                    semester = Semester.SPRING,
                    content = content,
                    rating = 4.0,
                    fromSnuev = true,
                ),
            ).id!!

    private fun JsonNode.scores(): Map<String, JsonNode?> = scoreKeys.associateWith { get(it) }

    private fun assertZeroScores(evaluation: JsonNode) {
        evaluation.scores().forEach { (key, value) ->
            assertEquals(0.0, value?.asDouble(), "$key: $evaluation")
            assertEquals(true, value?.isNumber, "$key: $evaluation")
        }
    }

    @Test
    fun `세부 점수가 없는 강의평은 구앱 조회 응답에서 0점으로 나가고 v2 응답은 비어 있다`() {
        val list = body(v1Get("/v1/ev-service/v1/lectures/$courseId/evaluations", legacyToken))
        assertZeroScores(list["content"].items().single())

        assertZeroScores(body(v1Get("/v1/ev-service/v1/evaluations/$othersEvaluationId", legacyToken)))
        assertZeroScores(
            body(v1Get("/v1/ev-service/v1/lectures/$courseId/evaluations/users/me", legacyToken))["evaluations"].items().single(),
        )
        assertZeroScores(body(v1Get("/v1/ev-service/v1/evaluations/users/me", legacyToken))["content"].items().single())

        val v2Evaluation = body(get("/v2/evaluations/$othersEvaluationId", v2Login("evreader")))
        listOf("gradeSatisfaction", "teachingSkill", "gains", "lifeBalance").forEach { key ->
            assertFalse(v2Evaluation.has(key) && !v2Evaluation[key].isNull, "$key: $v2Evaluation")
        }
        assertEquals(4.0, v2Evaluation["rating"].asDouble())
    }

    @Test
    fun `세부 점수 없이 내용만 고치면 수정 응답은 v1처럼 점수를 생략한다`() {
        val updated =
            v1Send(
                HttpMethod.PATCH,
                "/v1/ev-service/v1/evaluations/$myEvaluationId",
                """{"content":"내용만 고친 snuev 강의평입니다. 점수는 그대로 둡니다."}""",
                legacyToken,
            )

        assertEquals(200, updated.statusCode.value(), "body=${updated.body}")
        val evaluation = body(updated)
        assertEquals("내용만 고친 snuev 강의평입니다. 점수는 그대로 둡니다.", evaluation["content"].asString())
        scoreKeys.forEach { key -> assertFalse(evaluation.has(key), "$key: $evaluation") }
        // 고친 뒤에도 조회는 0점이다.
        assertZeroScores(body(v1Get("/v1/ev-service/v1/evaluations/$myEvaluationId", legacyToken)))
    }

    @Test
    fun `빈 cursor는 첫 페이지로 처리한다`() {
        listOf(
            "/v1/ev-service/v1/lectures/$courseId/evaluations",
            "/v1/ev-service/v1/evaluations/users/me",
            // 태그 강의평은 이 시드에서 비어 있을 수 있다. 여기서는 빈 cursor가 400이 아닌지가 요점이다.
            "/v1/ev-service/v1/tags/main/1/evaluations",
        ).forEach { path ->
            val firstPage = v1Get(path, legacyToken)
            val emptyCursor = v1Get("$path?cursor=", legacyToken)

            assertEquals(200, emptyCursor.statusCode.value(), "$path body=${emptyCursor.body}")
            assertEquals(body(firstPage), body(emptyCursor), path)
        }
        assertEquals(
            listOf(othersEvaluationId),
            body(
                v1Get("/v1/ev-service/v1/lectures/$courseId/evaluations?cursor=", legacyToken),
            )["content"].items().map { it["id"].asLong() },
        )
    }

    @Test
    fun `빈 값이 아닌 잘못된 cursor는 레거시 에러로 거절한다`() {
        assertV1Error(v1Get("/v1/ev-service/v1/lectures/$courseId/evaluations?cursor=not-a-cursor", legacyToken), 400, 20001)
    }
}
