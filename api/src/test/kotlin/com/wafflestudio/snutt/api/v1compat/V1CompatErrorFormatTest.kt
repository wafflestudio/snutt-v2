package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

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

    @Test
    fun `EV 경로에서 강의평 과목이 연결되지 않은 분반은 24002로 응답한다`() {
        val token = verifiedLegacyUser("evnocourse")
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val lecture = fixtures.lecture(autumn, courseTitle = "과목 없는 강의")

        assertEquals(null, lecture.courseId)
        assertV1Error(v1Post("/v1/ev-service/v1/semester-lectures/${lecture.id}/evaluations", evaluationBody, token), 404, 24002)
    }
}
