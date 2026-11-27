package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod

/**
 * 구앱이 에러 바디로 분기하는 경로(v1 errcode, EV errcode)를 고정한다.
 * 기대값은 v1 서버 코드에서 가져왔다. 출처는 각 테스트에 적는다.
 */
class V1CompatErrorFormatTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var userRepository: UserRepository

    private val evaluationBody =
        """{"content":"설명이 친절하고 과제 난이도도 적당한 좋은 강의입니다","grade_satisfaction":4.0,"teaching_skill":4.0,""" +
            """"gains":4.0,"life_balance":4.0,"rating":4.0}"""

    private fun verifiedLegacyUser(localId: String): String {
        val (token, _) = v1Register(localId)
        val user = userRepository.findByLocalIdAndActiveTrue(localId)!!
        user.isEmailVerified = true
        userRepository.save(user)
        return token
    }

    // snutt-ev ErrorType: LECTURE_NOT_FOUND(24001)은 EV lecture(= v2 course), SEMESTER_LECTURE_NOT_FOUND(24002)는
    // EV semester_lecture(= v2 lecture)다. EvaluationService.getEvaluationSummaryOfLecture / getSemesterLectureToWriteEvaluation.
    @Test
    fun `EV 경로에서 없는 과목은 24001, 없는 분반은 24002로 응답한다`() {
        val token = verifiedLegacyUser("evcode")

        assertV1Error(v1Get("/v1/ev-service/v1/lectures/987654/evaluation-summary", token), 404, 24001)
        assertV1Error(v1Post("/v1/ev-service/v1/semester-lectures/987654/evaluations", evaluationBody, token), 404, 24002)
    }

    // v1 ErrorWebFilter(snutt/api/.../filter/ErrorWebFilter.kt): SnuttException이 아니면 500 + DEFAULT_ERROR(errcode 0),
    // ResponseStatusException(매핑 없는 경로 404, 메서드 405, 미디어 타입 415)이면 그 상태 + errcode 0.
    // Android는 errcode 없는 바디를 파싱하지 못해 Unknown 에러가 된다.
    @Test
    fun `v1 경로의 처리되지 않은 예외는 v1 에러 형식의 500 errcode 0으로 응답한다`() {
        val (token, _) = v1Register("v1unhandled")

        // 메일 SDK가 런타임 예외를 던지면 MailLogService가 그대로 다시 던진다(SnuttException이 아니다).
        whenever(mailClient.send(any(), any(), any())).thenThrow(RuntimeException("mail sdk down"))
        val response = v1Post("/v1/user/email/verification", """{"email":"v1unhandled@snu.ac.kr"}""", token)

        assertV1Error(response, 500, 0)
        assertEquals("서버에 문제가 있으니, 잠시 후 다시 시도해주세요", body(response)["displayMessage"].asString())
    }

    @Test
    fun `v1 경로의 매핑 없는 경로와 메서드, 미디어 타입 오류도 v1 에러 형식으로 응답한다`() {
        val (token, _) = v1Register("v1notfound")

        assertV1Error(v1Get("/v1/no-such-endpoint", token), 404, 0)
        assertV1Error(v1Get("/v1/ev-service/v1/no-such-endpoint", token), 404, 0)
        assertV1Error(v1Delete("/v1/configs", token), 405, 0)
        assertV1Error(
            v1Send(HttpMethod.POST, "/v1/tables", "year=2026", token, headers = mapOf("Content-Type" to "text/plain")),
            415,
            0,
        )
        // API key 검증은 핸들러 매핑과 상관없이 v1 경로 전체에 걸린다. 매핑 없는 경로에서도 v1 형식으로 나가야 한다.
        assertV1Error(v1Send(HttpMethod.GET, "/v1/no-such-endpoint", apiKey = null), 403, 0x2000)
    }

    @Test
    fun `v2 경로의 오류는 그대로 ProblemDetail로 응답한다`() {
        val response = get("/v2/no-such-endpoint")

        assertEquals(404, response.statusCode.value())
        assertEquals("application/problem+json", response.headers.contentType.toString())
        assertEquals(false, body(response).has("errcode"))
    }

    @Test
    fun `EV 경로에서 강의평 과목이 연결되지 않은 분반은 24002로 응답한다`() {
        val token = verifiedLegacyUser("evnocourse")
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val lecture = fixtures.lecture(autumn, courseTitle = "과목 없는 강의")

        assertEquals(null, lecture.courseId)
        assertV1Error(v1Post("/v1/ev-service/v1/semester-lectures/${lecture.id}/evaluations", evaluationBody, token), 404, 24002)
    }
}
