package com.wafflestudio.snutt.api.diary

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.api.testutil.saveLectureWithTimes
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.coursebook.repository.CoursebookRepository
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryDailyClassTypeRepository
import com.wafflestudio.snutt.core.domain.diary.repository.DiaryQuestionRepository
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
 * M5b DoD: 강의 일기장 — 질문지 생성, 대상 강의 추천, 제출/조회/삭제 (PLAN.md §7 M5)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiaryIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("diary_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var coursebookRepository: CoursebookRepository

    @Autowired
    lateinit var lectureRepository: LectureRepository

    @Autowired lateinit var lectureClassTimeRepository: com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository

    @Autowired
    lateinit var timetableRepository: TimetableRepository

    @Autowired
    lateinit var timetableLectureRepository: TimetableLectureRepository

    @Autowired
    lateinit var diaryDailyClassTypeRepository: DiaryDailyClassTypeRepository

    @Autowired
    lateinit var diaryQuestionRepository: DiaryQuestionRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @LocalServerPort
    var port = 0

    private lateinit var token: String
    private var userId: Long = 0
    private lateinit var lectureIds: List<String>
    private lateinit var classTypeIds: List<Long>
    private lateinit var questionIds: List<Long>

    @BeforeAll
    fun seedDatabase() {
        coursebookRepository.save(Coursebook(year = 2026, semester = Semester.AUTUMN))

        val lectures =
            listOf("일기장강의1", "일기장강의2").map { title ->
                saveLectureWithTimes(
                    lectureRepository,
                    lectureClassTimeRepository,
                    Lecture(
                        year = 2026,
                        semester = Semester.AUTUMN,
                        courseNumber = "4190.$title",
                        lectureNumber = "001",
                        courseTitle = title,
                        instructor = "교수",
                    ),
                    listOf(ClassPlaceAndTime(DayOfWeek.MONDAY, "302-101", 570, 660)),
                )
            }
        lectureIds = lectures.map { it.externalId }

        // 어드민이 오늘 한 일 유형/질문 등록
        val classTypes =
            listOf("수업듣기", "공부하기").map { name ->
                diaryDailyClassTypeRepository.save(DiaryDailyClassType(name = name))
            }
        classTypeIds = classTypes.mapNotNull { it.id }
        val questions =
            listOf(
                Triple("오늘 수업은 어땠나요?", "수업", listOf("좋아요", "싫어요")),
                Triple("오늘 공부는 어땠나요?", "공부", listOf("잘했어요", "못했어요")),
                Triple("오늘 하루는 어땠나요?", "하루", listOf("행복", "우울")),
            ).map { (question, short, answers) ->
                diaryQuestionRepository.save(
                    DiaryQuestion(
                        question = question,
                        shortQuestion = short,
                        answerList = answers,
                        shortAnswerList = answers,
                        targetDailyClassTypeIdList = classTypeIds,
                    ),
                )
            }
        questionIds = questions.mapNotNull { it.id }

        // 사용자 + 대표 시간표 + 강의 2개
        val register =
            post(
                "/v2/auth/register",
                """{"localId":"diaryuser","password":"password1","email":"diary@snu.ac.kr"}""",
                withAuth = false,
            )
        token = asMap(register)["accessToken"] as String
        userId = userRepository.findByLocalIdAndActiveTrue("diaryuser")!!.id!!
        val timetable =
            timetableRepository.save(
                Timetable(
                    userId = userId,
                    year = 2026,
                    semester = Semester.AUTUMN,
                    title = "나의 시간표",
                    theme = com.wafflestudio.snutt.core.common.enums.BasicThemeType.FALL,
                    isPrimary = true,
                ),
            )
        lectureRepository.findAll().forEach { lecture ->
            timetableLectureRepository.save(
                TimetableLecture(timetableId = timetable.id!!, lectureId = lecture.id, colorIndex = 1),
            )
        }
    }

    // 일기장 제출은 24시간 이내 대상 제외에 영향을 주므로 테스트 간 비운다
    @BeforeEach
    fun cleanSubmissions() {
        diarySubmissionRepository.deleteAll()
    }

    @Autowired
    lateinit var diarySubmissionRepository: com.wafflestudio.snutt.core.domain.diary.repository.DiarySubmissionRepository

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
        withAuth: Boolean = true,
    ): ResponseEntity<Any> {
        val spec = client().post().uri(uri)
        if (withAuth) spec.headers { it.setBearerAuth(token) }
        return spec.body(body).retrieve().toEntity(Any::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun get(uri: String): ResponseEntity<Any> =
        client()
            .get()
            .uri(uri)
            .headers { it.setBearerAuth(token) }
            .retrieve()
            .toEntity(Any::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun delete(uri: String): ResponseEntity<Any> =
        client()
            .delete()
            .uri(uri)
            .headers { it.setBearerAuth(token) }
            .retrieve()
            .toEntity(Any::class.java)

    private fun asMap(response: ResponseEntity<Any>): Map<String, Any?> = response.body as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun asList(response: ResponseEntity<Any>): List<Map<String, Any?>> = response.body as List<Map<String, Any?>>

    @Test
    fun `질문지와 대상 강의가 생성된다`() {
        val questionnaire =
            post(
                "/v2/diary/questionnaire",
                """{"lectureId":"${lectureIds[0]}","dailyClassTypes":["수업듣기","공부하기"]}""",
            )
        assertEquals(200, questionnaire.statusCode.value())
        val body = asMap(questionnaire)
        assertEquals("일기장강의1", body["courseTitle"])
        assertEquals(3, (body["questions"] as List<*>).size)
        // 현재 강의는 다음 대상에서 제외된다
        val nextLecture = body["nextLecture"] as Map<*, *>
        assertEquals("일기장강의2", nextLecture["courseTitle"])
    }

    @Test
    fun `대상 강의 추천은 대표 시간표 기준이다`() {
        val target = get("/v2/diary/target?year=2026&semester=3")
        assertEquals(200, target.statusCode.value())
        val body = asMap(target)
        assertTrue(body["courseTitle"].toString().startsWith("일기장강의"))
    }

    @Test
    fun `일기 제출과 내 기록 조회와 삭제`() {
        val submit =
            post(
                "/v2/diary",
                """{"lectureId":"${lectureIds[0]}","dailyClassTypes":["수업듣기"],"questionAnswers":[{"questionId":${questionIds[0]},"answerIndex":0}],"comment":"좋은 하루였다"}""",
            )
        assertEquals(200, submit.statusCode.value())

        val my = get("/v2/diary/my")
        assertEquals(200, my.statusCode.value())
        val groups = asList(my)
        assertEquals(1, groups.size)
        assertEquals(2026, groups[0]["year"])
        val submissions = groups[0]["submissions"] as List<*>
        assertEquals(1, submissions.size)
        val summary = submissions[0] as Map<*, *>
        assertEquals("일기장강의1", summary["courseTitle"])
        val replies = summary["shortQuestionReplies"] as List<*>
        assertEquals(1, replies.size)
        assertEquals("좋아요", (replies[0] as Map<*, *>)["shortAnswer"])

        // 1,000자 초과 댓글 거부
        val tooLong =
            post(
                "/v2/diary",
                """{"lectureId":"${lectureIds[0]}","dailyClassTypes":[],"questionAnswers":[],"comment":"${"가".repeat(1001)}"}""",
            )
        assertEquals(400, tooLong.statusCode.value())

        // 삭제
        val submissionId = summary["id"] as String
        assertEquals(200, delete("/v2/diary/$submissionId").statusCode.value())
        assertEquals(0, asList(get("/v2/diary/my")).size)
    }

    @Test
    fun `오늘 한 일 유형 목록`() {
        val types = asList(get("/v2/diary/daily-class-types"))
        assertEquals(2, types.size)
        assertEquals(setOf("수업듣기", "공부하기"), types.map { it["name"] }.toSet())
    }
}
