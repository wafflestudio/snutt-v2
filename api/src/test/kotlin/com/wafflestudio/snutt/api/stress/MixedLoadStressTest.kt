package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.ErrorType.CANNOT_DELETE_LAST_TIMETABLE
import com.wafflestudio.snutt.core.common.error.ErrorType.DUPLICATE_EVALUATION
import com.wafflestudio.snutt.core.common.error.ErrorType.DUPLICATE_EVALUATION_LIKE
import com.wafflestudio.snutt.core.common.error.ErrorType.DUPLICATE_LECTURE
import com.wafflestudio.snutt.core.common.error.ErrorType.DUPLICATE_NICKNAME
import com.wafflestudio.snutt.core.common.error.ErrorType.DUPLICATE_TIMETABLE_TITLE
import com.wafflestudio.snutt.core.common.error.ErrorType.EVALUATION_NOT_FOUND
import com.wafflestudio.snutt.core.common.error.ErrorType.EVALUATION_NOT_LIKED
import com.wafflestudio.snutt.core.common.error.ErrorType.INVALID_TIMETABLE_TITLE
import com.wafflestudio.snutt.core.common.error.ErrorType.LECTURE_TIME_OVERLAP
import com.wafflestudio.snutt.core.common.error.ErrorType.TIMETABLE_LECTURE_NOT_FOUND
import com.wafflestudio.snutt.core.common.error.ErrorType.TIMETABLE_NOT_FOUND
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseAggregateUpdater
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.test.StressTest
import com.wafflestudio.snutt.core.test.concurrency.runConcurrently
import com.wafflestudio.snutt.core.test.stress.DomainInvariants
import com.wafflestudio.snutt.core.test.stress.FailureKind
import com.wafflestudio.snutt.core.test.stress.MySqlProbe
import com.wafflestudio.snutt.core.test.stress.StressRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import java.time.Duration
import kotlin.random.Random

/**
 * 여러 엔드포인트를 섞어 같은 사용자에게 몰리는 부하. 사용자 한 명당 스레드 약 10개가 아래 요청을 무작위로 보낸다.
 *
 * 락 순서가 다른 경로를 일부러 섞었다(코드에서 본 락 획득 순서):
 * - 강의 추가·삭제(`TimetableLectureService`): timetable 행 FOR UPDATE → timetable_lecture
 * - 시간표 삭제·복사·추가(`TimetableService`): user 행 FOR UPDATE → timetable insert/delete(삽입은 theme 행 FK S락)
 * - 시간표 테마 변경(`modifyTimetableTheme`): timetable FOR UPDATE → theme_id 갱신(새 theme 행 FK S락) → timetable_lecture
 * - 테마 팔레트 변경(`modifyTheme`): (user_id, theme_id)로 timetable FOR UPDATE(인덱스 범위·gap 락) → timetable_lecture → theme 행 X락(커밋 시 flush)
 * - 기본 테마 지정: user FOR UPDATE → user_preference(theme 행 FK S락)
 * - 강의평 작성: course FOR UPDATE → evaluation insert(user 행 FK S락). 삭제: evaluation FOR UPDATE → course. 공감: evaluation FOR UPDATE → evaluation_like
 * - 닉네임 변경: user 행 UPDATE(X락)
 *
 * 위험한 조합: 팔레트 변경은 시간표들을 잠근 뒤 theme 행을 X로 잠그고, 시간표 복사·추가·테마 변경은 같은 theme 행에 FK S락을 먼저 잡은 뒤
 * 같은 (user, theme) 인덱스 범위에 쓴다. 사용자 행 X락(닉네임, 시간표 목록)과 user FK S락(강의평 작성)도 섞인다.
 *
 * 하위 클래스가 연산 구성을 정한다. 반복마다 [DomainInvariants]와 사용자별 시간표 1개 이상을 검증하고, 5xx·데드락·lock wait timeout·풀 타임아웃을 센다.
 */
abstract class AbstractMixedLoadStressTest(
    private val scenarioName: String,
    excludedOperations: Set<String> = emptySet(),
) : AbstractStressTest() {
    private val operations = OPERATIONS.filterNot { it in excludedOperations }

    private class Setup(
        val users: List<User>,
        val customThemes: Map<Long, List<Long>>,
        val builtinThemes: List<Long>,
        val lectures: List<Lecture>,
        val evaluationLectures: List<Lecture>,
        val likeTargets: List<Long>,
    )

    private val invariants by lazy { DomainInvariants(applicationContext) }

    @Test
    fun `강의·시간표·테마·강의평·사용자 정보 요청을 같은 사용자들에게 섞어 보낸다`() =
        stress(scenarioName) {
            for (iteration in 1..settings.iterations) {
                if (shouldStop()) break
                iteration(iteration) {
                    resetForIteration()
                    val setup = prepare(Random(settings.seed + iteration))
                    try {
                        runConcurrently(settings.threads, LOAD_TIMEOUT) { thread ->
                            val random = Random(settings.seed * 31 + iteration * 10_007L + thread)
                            val user = setup.users[thread % setup.users.size]
                            repeat(settings.opsPerThread) { operate(this, setup, user, random) }
                        }
                    } catch (e: AssertionError) {
                        fail(FailureKind.CLIENT_TIMEOUT, "mixed", e.message.orEmpty())
                        return@iteration
                    }
                    invariants.assertHolds()
                    val noTimetable = invariants.usersWithoutTimetable(setup.users.map { it.id!! })
                    if (noTimetable.isNotEmpty()) throw AssertionError(noTimetable.joinToString())
                }
            }
        }

    private fun prepare(random: Random): Setup {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val themeService: TimetableThemeService = applicationContext.getBean()
        // 평일 09:00~17:00, 30분 단위 시작, 75분 강의 40개. 서로 겹치는 것이 많아 시간 겹침 거절도 섞인다.
        val lectures =
            (0 until 40).map {
                val start = 540 + random.nextInt(17) * 30
                fixtures.lecture(
                    autumn,
                    times =
                        listOf(
                            ClassPlaceAndTime(
                                DayOfWeek.entries[random.nextInt(5)],
                                "301-1$it",
                                start,
                                start + 75,
                            ),
                        ),
                )
            }
        val userCount = (settings.threads / THREADS_PER_USER).coerceAtLeast(2)
        val users = (0 until userCount).map { fixtures.user() }
        val customThemes =
            users.associate { user ->
                val nine = themeService.addTheme(user.id!!, "아홉 색", (1..9).map { ColorSet("#00000$it", "#ffffff") })
                val three = themeService.addTheme(user.id!!, "세 색", (1..3).map { ColorSet("#10000$it", "#ffffff") })
                fixtures.timetable(user, autumn, title = "시간표 A", isPrimary = true, themeId = nine.id)
                fixtures.timetable(user, autumn, title = "시간표 B", isPrimary = false, themeId = three.id)
                fixtures.timetable(user, autumn, title = "시간표 C", isPrimary = false)
                user.id!! to listOf(nine.id, three.id)
            }
        val builtinThemes = listOf(themeService.builtinThemeId("snutt"), themeService.builtinThemeId("fall"))

        val courseRepository: CourseRepository = applicationContext.getBean()
        val evaluationRepository: EvaluationRepository = applicationContext.getBean()
        val authors = (0 until 2).map { fixtures.user() }
        val evaluationLectures =
            (0 until 3).map { index ->
                val course = courseRepository.save(Course(courseNumber = "M$index", instructor = "교수$index", title = "강의평 과목 $index"))
                fixtures.lecture(autumn, courseNumber = course.courseNumber, courseTitle = course.title) { courseId = course.id }
            }
        val likeTargets =
            evaluationLectures.flatMap { lecture ->
                authors.map { author ->
                    evaluationRepository
                        .save(
                            Evaluation(
                                courseId = lecture.courseId!!,
                                userId = author.id!!,
                                year = lecture.year,
                                semester = lecture.semester,
                                content = "미리 작성된 강의평입니다. 내용은 스무 자를 넘깁니다.",
                                gradeSatisfaction = 4.0,
                                teachingSkill = 4.0,
                                gains = 4.0,
                                lifeBalance = 4.0,
                                rating = 4.0,
                            ),
                        ).id!!
                }
            }
        val aggregateUpdater: CourseAggregateUpdater = applicationContext.getBean()
        evaluationLectures.forEach { aggregateUpdater.update(it.courseId!!) }
        return Setup(users, customThemes, builtinThemes, lectures, evaluationLectures, likeTargets)
    }

    private fun operate(
        run: StressRun,
        setup: Setup,
        user: User,
        random: Random,
    ) {
        val userId = user.id!!
        val token = token(userId)
        val operation = operations[random.nextInt(operations.size)]

        fun call(
            method: String,
            path: String,
            body: Any? = null,
            vararg allowed: ErrorType,
        ) {
            val response = http.send(method, path, token, body)
            val ok = response.isSuccess || response.errorType in allowed
            run.record(operation, response.outcome, ok, detail = "$method $path -> ${response.status} ${response.body.take(300)}")
        }

        val timetableId = randomId(random, "SELECT id FROM timetable WHERE user_id = ?", userId)
        when (operation) {
            "addLecture" ->
                call(
                    "POST",
                    "/v2/timetables/$timetableId/lectures",
                    mapOf("lectureId" to setup.lectures.random(random).id),
                    LECTURE_TIME_OVERLAP,
                    DUPLICATE_LECTURE,
                    TIMETABLE_NOT_FOUND,
                )
            "addCustomLecture" -> {
                val start = 540 + random.nextInt(17) * 30
                val time = ClassPlaceAndTime(DayOfWeek.entries[random.nextInt(5)], "직접", start, start + 50)
                call(
                    "POST",
                    "/v2/timetables/$timetableId/lectures/custom",
                    mapOf("courseTitle" to "직접 추가", "classPlaceAndTimes" to listOf(time)),
                    LECTURE_TIME_OVERLAP,
                    TIMETABLE_NOT_FOUND,
                )
            }
            "deleteLecture" -> {
                val entryId =
                    randomId(
                        random,
                        "SELECT tl.id FROM timetable_lecture tl WHERE tl.timetable_id = ?",
                        timetableId,
                    )
                call("DELETE", "/v2/timetables/$timetableId/lectures/$entryId", null, TIMETABLE_NOT_FOUND, TIMETABLE_LECTURE_NOT_FOUND)
            }
            "deleteTimetable" -> call("DELETE", "/v2/timetables/$timetableId", null, CANNOT_DELETE_LAST_TIMETABLE, TIMETABLE_NOT_FOUND)
            "copyTimetable" ->
                if (timetableCount(userId) < MAX_TIMETABLES) {
                    call("POST", "/v2/timetables/$timetableId/copy", null, TIMETABLE_NOT_FOUND, INVALID_TIMETABLE_TITLE)
                } else {
                    call("GET", "/v2/timetables/$timetableId", null, TIMETABLE_NOT_FOUND)
                }
            "addTimetable" ->
                if (timetableCount(userId) < MAX_TIMETABLES) {
                    call(
                        "POST",
                        "/v2/timetables",
                        mapOf("year" to 2026, "semester" to Semester.AUTUMN, "title" to "새 시간표 ${random.nextInt(5)}"),
                        DUPLICATE_TIMETABLE_TITLE,
                    )
                } else {
                    call("GET", "/v2/timetables", null)
                }
            "modifyThemeColors" -> {
                val colors = (1..(2 + random.nextInt(8))).map { ColorSet("#20000$it", "#ffffff") }
                call("PATCH", "/v2/themes/${setup.customThemes.getValue(userId).random(random)}", mapOf("colors" to colors))
            }
            "modifyTimetableTheme" -> {
                val themeId = (setup.customThemes.getValue(userId) + setup.builtinThemes).random(random)
                call("PUT", "/v2/timetables/$timetableId/theme", mapOf("themeId" to themeId), TIMETABLE_NOT_FOUND)
            }
            "setDefaultTheme" -> {
                val themeId = (setup.customThemes.getValue(userId) + setup.builtinThemes).random(random)
                call("POST", "/v2/themes/$themeId/default")
            }
            "createEvaluation" ->
                call(
                    "POST",
                    "/v2/lectures/${setup.evaluationLectures.random(random).id}/evaluations",
                    EvaluationWriteRequest(
                        content = "부하 중에 작성한 강의평입니다. 스무 자를 넘깁니다.",
                        gradeSatisfaction = 3.0,
                        teachingSkill = 3.5,
                        gains = 4.0,
                        lifeBalance = 2.5,
                        rating = 1.0 + random.nextInt(5),
                    ),
                    DUPLICATE_EVALUATION,
                )
            "deleteEvaluation" -> {
                val evaluationId = randomId(random, "SELECT id FROM evaluation WHERE user_id = ? AND NOT is_hidden", userId)
                call("DELETE", "/v2/evaluations/$evaluationId", null, EVALUATION_NOT_FOUND)
            }
            "likeEvaluation" ->
                call("POST", "/v2/evaluations/${setup.likeTargets.random(random)}/like", null, DUPLICATE_EVALUATION_LIKE)
            "cancelLikeEvaluation" ->
                call("DELETE", "/v2/evaluations/${setup.likeTargets.random(random)}/like", null, EVALUATION_NOT_LIKED)
            "updateNickname" -> call("PATCH", "/v2/users/me", mapOf("nickname" to NICKNAMES.random(random)), DUPLICATE_NICKNAME)
            "getTimetable" -> call("GET", "/v2/timetables/$timetableId", null, TIMETABLE_NOT_FOUND)
            else -> error(operation)
        }
    }

    /** 대상 id를 테스트 쪽 연결로 고른다. 없으면(모두 지워졌으면) 존재하지 않는 id를 보내 NOT_FOUND 경로를 탄다. */
    private fun randomId(
        random: Random,
        sql: String,
        arg: Long,
    ): Long = MySqlProbe.jdbc.queryForList(sql, Long::class.java, arg).randomOrNull(random) ?: Long.MAX_VALUE

    private fun timetableCount(userId: Long): Int =
        MySqlProbe.jdbc.queryForObject("SELECT COUNT(*) FROM timetable WHERE user_id = ?", Int::class.java, userId)!!

    companion object {
        const val THREADS_PER_USER = 10
        const val MAX_TIMETABLES = 6
        val NICKNAMES = listOf("부하테스트", "동시사용자", "스트레스")
        val LOAD_TIMEOUT: Duration = Duration.ofMinutes(5)

        /** 가중치는 반복 횟수로 준다. 강의 추가·삭제와 테마 변경을 많이 섞는다. */
        val OPERATIONS =
            listOf(
                "addLecture" to 4,
                "addCustomLecture" to 1,
                "deleteLecture" to 3,
                "deleteTimetable" to 1,
                "copyTimetable" to 2,
                "addTimetable" to 1,
                "modifyThemeColors" to 2,
                "modifyTimetableTheme" to 2,
                "setDefaultTheme" to 1,
                "createEvaluation" to 1,
                "deleteEvaluation" to 1,
                "likeEvaluation" to 2,
                "cancelLikeEvaluation" to 1,
                "updateNickname" to 2,
                "getTimetable" to 1,
            ).flatMap { (name, weight) -> List(weight) { name } }
    }
}

/**
 * 테마 팔레트 변경(`PATCH /v2/themes/{id}`)을 뺀 혼합 부하. 통과해야 한다. 뺀 경로는 재현된 버그라 known-failure로 둔다.
 * 시간표 테마 변경은 예전에 조회와 겹치면 IndexOutOfBounds 500이 났다(조회가 옛 테마에 새 index를 붙임). 지금은 팔레트 밖 index를 팔레트 안으로 접는다.
 */
@StressTest
class MixedLoadStressTest : AbstractMixedLoadStressTest("mixed-load", excludedOperations = setOf("modifyThemeColors"))
