package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp

/**
 * 구앱·강의평 웹이 캐시해 둔 id로 전환 뒤에 호출할 때.
 * - EV id(강의평 웹 URL의 lecture id, 강의평 id)는 이관이 그대로 보존한다(CourseStep: EV lecture id = course id, EvaluationStep).
 * - SNUTT id(Mongo ObjectId hex)는 v2 Long PK로 바뀌고 매핑을 남기지 않는다. 캐시된 hex id는 같은 리소스를 가리킬 수 없으므로,
 *   구앱이 처리하는 v1 에러 형식으로 결정적으로 실패해야 한다(시간표 조회는 404, 나머지 경로 id는 400 40001).
 * - 알림 딥링크의 ObjectId는 이관이 새 id로 바꿔 쓴다(NotificationStep.rewriteDeeplink, ValidateStep이 남은 hex를 검사).
 */
class IdContinuityScenarioTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    private val legacyEvLectureId = 7_301L
    private val legacyEvaluationId = 55_501L

    private fun seedMigratedEv(authorId: Long): Long {
        val now = Timestamp.from(clock.instant())
        jdbcTemplate.update(
            "INSERT INTO course (id, course_number, instructor, title, eval_count, avg_rating, avg_grade_satisfaction, " +
                "avg_teaching_skill, avg_gains, avg_life_balance, created_at, updated_at) " +
                "VALUES (?, '4190.310', '이교수', '운영체제', 1, 4.0, 3.0, 5.0, 4.0, 2.0, ?, ?)",
            legacyEvLectureId,
            now,
            now,
        )
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val lecture =
            fixtures.lecture(autumn, courseTitle = "운영체제", courseNumber = "4190.310") {
                instructor = "이교수"
                courseId = legacyEvLectureId
            }
        jdbcTemplate.update("UPDATE course SET latest_lecture_id = ? WHERE id = ?", lecture.id, legacyEvLectureId)
        jdbcTemplate.update(
            "INSERT INTO evaluation (id, course_id, user_id, year, semester, content, grade_satisfaction, teaching_skill, gains, " +
                "life_balance, rating, is_hidden, is_reported, from_snuev, created_at, updated_at) " +
                "VALUES (?, ?, ?, 2025, 3, '이관된 강의평입니다. 과제가 많아요', 3.0, 5.0, 4.0, 2.0, 4.0, FALSE, FALSE, TRUE, ?, ?)",
            legacyEvaluationId,
            legacyEvLectureId,
            authorId,
            now,
            now,
        )
        return lecture.id!!
    }

    @Test
    fun `강의평 웹이 캐시한 EV lecture id와 강의평 id는 이관 뒤에도 같은 대상을 가리킨다`() {
        val author = migratedUser("evauthor")
        val reader = migratedUser("evreader")
        val lectureId = seedMigratedEv(author.id)

        val summary = body(v1Get("/ev-service/v1/lectures/$legacyEvLectureId/evaluation-summary", reader.token))
        assertEquals(legacyEvLectureId, summary["id"].asLong())
        assertEquals("운영체제", summary["title"].asString())
        assertEquals(4.0, summary["evaluation"]["avg_rating"].asDouble())

        val evaluations = body(v1Get("/ev-service/v1/lectures/$legacyEvLectureId/evaluations", reader.token))["content"]
        assertEquals(listOf(legacyEvaluationId), evaluations.items().map { it["id"].asLong() })

        val evaluation = body(v1Get("/ev-service/v1/evaluations/$legacyEvaluationId", author.token))
        assertEquals("이관된 강의평입니다. 과제가 많아요", evaluation["content"].asString())
        assertEquals(true, evaluation["from_snuev"].asBoolean())
        assertEquals(true, evaluation["is_modifiable"].asBoolean())
        assertEquals(false, body(v1Get("/ev-service/v1/evaluations/$legacyEvaluationId", reader.token))["is_modifiable"].asBoolean())

        // 앱 강의 상세의 강의평 요약도 같은 EV lecture id를 준다(Android는 이 값으로 강의평 웹을 연다)
        val appSummary = body(v1Get("/v1/ev/lectures/$lectureId/summary", reader.token))
        assertEquals(legacyEvLectureId.toString(), appSummary["evLectureId"].asString())
    }

    @Test
    fun `구앱이 캐시한 ObjectId hex로 호출하면 v1 에러 형식으로 결정적으로 실패한다`() {
        val migrated = migratedUser("hexcaller")
        val user = userRepository.findById(migrated.id).get()
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val timetableId = fixtures.timetable(user, autumn).id!!
        val hex = "65f1c2a9e4b0d3a1f2c3b4d5" // v1 시간표·강의 id 형식(Mongo ObjectId 24자 hex)

        // 마지막으로 본 시간표 id: 404 40400(시간표 없음)이면 앱이 목록을 다시 받는다
        assertV1Error(v1Get("/v1/tables/$hex", migrated.token), 404, 40400)
        assertV1Error(v1Put("/v1/tables/$hex", """{"title":"x"}""", migrated.token), 400, 40001)
        assertV1Error(v1Post("/v1/tables/$timetableId/lecture/$hex", "{}", migrated.token), 400, 40001)
        assertV1Error(v1Delete("/v1/tables/$timetableId/lecture/$hex", migrated.token), 400, 40001)
        assertV1Error(v1Get("/v1/ev/lectures/$hex/summary", migrated.token), 400, 40001)
        assertV1Error(v1Post("/v1/vacancy-notifications/lectures/$hex", "{}", migrated.token), 400, 40001)
        assertV1Error(v1Post("/v1/bookmarks/lecture", """{"lecture_id":"$hex"}""", migrated.token), 400, 40002)
        // 실패한 요청이 데이터를 바꾸지 않았다
        assertEquals(listOf(timetableId.toString()), body(v1Get("/v1/tables", migrated.token)).items().map { it["_id"].asString() })
    }

    @Test
    fun `이관된 알림의 딥링크는 새 id를 담아 그대로 내려간다`() {
        val migrated = migratedUser("deeplinkuser")
        val rewritten = "snutt://timetable-lecture?timetableId=12&lectureId=34"
        notificationRepository.save(
            Notification(
                userId = migrated.id,
                title = "수강편람 업데이트",
                message = "강의가 변경되었습니다",
                type = NotificationType.LECTURE_UPDATE,
                deeplink = rewritten,
            ),
        )

        val notification = body(v1Get("/v1/notification", migrated.token)).items().single()

        assertEquals(rewritten, notification["deeplink"].asString())
        assertEquals(NotificationType.LECTURE_UPDATE.value, notification["type"].asInt())
        assertEquals(migrated.id.toString(), notification["user_id"].asString())
        assertFalse(Regex("[0-9a-fA-F]{24}").containsMatchIn(notification["deeplink"].asString()))
    }
}
