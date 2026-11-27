package com.wafflestudio.snutt.api.concurrency

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationLike
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationLikeRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseAggregateUpdater
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceDto
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceItem
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.test.concurrency.ConcurrentOutcome
import com.wafflestudio.snutt.core.test.fixture.ScenarioFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition

/**
 * PR CI 동시성 테스트와 stress 작업이 함께 쓰는 시나리오 모음. 각 메서드가 DB 상태를 준비하고 [ConcurrencyScenario]를 돌려준다.
 * 요청 수 [requests]는 holder 1 + contender N이다. contender가 Hikari 풀(기본 10)을 넘지 않게 PR CI에서는 5 안팎으로 둔다.
 */
class ConcurrencyScenarios(
    private val beans: ApplicationContext,
    private val fixtures: ScenarioFixtures,
    private val actions: ScenarioActions = ServiceScenarioActions(beans),
) {
    private val jdbc: JdbcTemplate = beans.getBean()

    private fun count(
        sql: String,
        vararg args: Any,
    ): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    private val mondayMorning = ClassPlaceAndTime(DayOfWeek.MONDAY, "301-101", 600, 675)

    private fun scenario(
        requests: Int,
        isolation: Int = TransactionDefinition.ISOLATION_DEFAULT,
        request: (Int) -> Unit,
        verify: (ConcurrentOutcome<Unit>) -> Unit,
        verifyUnordered: (ConcurrentOutcome<Unit>) -> Unit = verify,
    ): ConcurrencyScenario = LambdaScenario(requests, isolation, request, verify, verifyUnordered)

    private class LambdaScenario(
        override val requests: Int,
        override val isolation: Int,
        private val onRequest: (Int) -> Unit,
        private val onVerify: (ConcurrentOutcome<Unit>) -> Unit,
        private val onVerifyUnordered: (ConcurrentOutcome<Unit>) -> Unit,
    ) : ConcurrencyScenario {
        override fun request(index: Int) = onRequest(index)

        override fun verify(outcome: ConcurrentOutcome<Unit>) = onVerify(outcome)

        override fun verifyUnordered(outcome: ConcurrentOutcome<Unit>) = onVerifyUnordered(outcome)
    }

    // ---- 시간표 (timetable 행 PESSIMISTIC_WRITE) ----

    /** 같은 시간표에 서로 시간이 겹치는 강의 [requests]개를 동시에 넣는다. 하나만 들어가고 나머지는 시간 겹침이다. */
    fun overlappingLecturesIntoOneTimetable(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val timetable = fixtures.timetable(user, autumn)
        val lectures = (0 until requests).map { fixtures.lecture(autumn, times = listOf(mondayMorning)) }
        return scenario(
            requests,
            request = { actions.addLecture(user.id!!, timetable.id!!, lectures[it].id!!) },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.LECTURE_TIME_OVERLAP)
                assertEquals(1, count("SELECT COUNT(*) FROM timetable_lecture WHERE timetable_id = ?", timetable.id!!))
            },
        )
    }

    /** 같은 강의를 같은 시간표에 [requests]번 동시에 넣는다. 한 번만 들어가고 나머지는 중복 강의다. */
    fun sameLectureIntoOneTimetable(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val timetable = fixtures.timetable(user, autumn)
        val lecture = fixtures.lecture(autumn, times = listOf(mondayMorning))
        return scenario(
            requests,
            request = { actions.addLecture(user.id!!, timetable.id!!, lecture.id!!) },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.DUPLICATE_LECTURE)
                assertEquals(1, count("SELECT COUNT(*) FROM timetable_lecture WHERE timetable_id = ?", timetable.id!!))
            },
        )
    }

    /**
     * 사용자 테마의 팔레트를 9색에서 2색으로 줄이는 요청(요청 0)과 같은 시간표에 강의를 추가하는 요청이 겹친다.
     * 팔레트 축소가 시간표를 잠그므로, 추가 요청은 줄어든 팔레트를 보고 색을 고른다. 모든 palette_index가 2 미만이어야 한다.
     */
    fun themePaletteShrinkWhileAddingLectures(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val themeService: TimetableThemeService = beans.getBean()
        val nineColors = (1..9).map { ColorSet("#00000$it", "#ffffff") }
        val theme = themeService.addTheme(user.id!!, "내 테마", nineColors)
        val timetable = fixtures.timetable(user, autumn, themeId = theme.id)
        // 0, 1번 색이 이미 쓰이고 있으므로 9색 팔레트를 보면 새 강의는 반드시 2번 이상 색을 받는다.
        fixtures.addLecture(timetable, fixtures.lecture(autumn), paletteIndex = 0)
        fixtures.addLecture(timetable, fixtures.lecture(autumn), paletteIndex = 1)
        return scenario(
            requests,
            request = { index ->
                if (index == 0) {
                    actions.modifyThemeColors(user.id!!, theme.id, nineColors.take(2))
                } else {
                    // 평일 5일 × 30분 칸 48개. 요청 240개까지 서로 겹치지 않는다.
                    val start = (index / 5) * 30
                    val time = ClassPlaceAndTime(DayOfWeek.entries[index % 5], "301-10$index", start, start + 25)
                    actions.addCustomLecture(user.id!!, timetable.id!!, "직접 추가 $index", time)
                }
            },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                val indices =
                    jdbc.queryForList(
                        "SELECT palette_index FROM timetable_lecture WHERE timetable_id = ?",
                        Int::class.java,
                        timetable.id!!,
                    )
                assertEquals(requests + 1, indices.size)
                assertTrue(indices.all { it!! < 2 }) { "팔레트 범위를 벗어난 palette_index: $indices" }
            },
        )
    }

    // ---- 시간표 목록 (user 행 PESSIMISTIC_WRITE) ----

    /** 시간표가 없는 학기에 서로 다른 제목의 시간표 [requests]개를 동시에 만든다. 모두 만들어지고 대표 시간표는 하나다. */
    fun firstTimetablesOfSemester(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        return scenario(
            requests,
            request = { actions.addTimetable(user.id!!, autumn.year, autumn.semester, "시간표 $it") },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(requests, count("SELECT COUNT(*) FROM timetable WHERE user_id = ?", user.id!!))
                assertEquals(1, count("SELECT COUNT(*) FROM timetable WHERE user_id = ? AND is_primary", user.id!!))
            },
        )
    }

    /** 같은 제목의 시간표를 [requests]번 동시에 만든다. 하나만 만들어지고 나머지는 제목 중복이다. */
    fun sameTitleTimetables(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        return scenario(
            requests,
            request = { actions.addTimetable(user.id!!, autumn.year, autumn.semester, "같은 제목") },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.DUPLICATE_TIMETABLE_TITLE)
                assertEquals(1, count("SELECT COUNT(*) FROM timetable WHERE user_id = ?", user.id!!))
            },
        )
    }

    /** 시간표 [requests]개를 가진 사용자가 모두를 동시에 지운다. 마지막 하나는 남는다. */
    fun deleteEveryTimetable(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val timetables = (0 until requests).map { fixtures.timetable(user, autumn, title = "시간표 $it", isPrimary = it == 0) }
        return scenario(
            requests,
            request = { actions.deleteTimetable(user.id!!, timetables[it].id!!) },
            verify = { outcome ->
                outcome.assertOutcome(successes = requests - 1, ErrorType.CANNOT_DELETE_LAST_TIMETABLE)
                assertEquals(1, count("SELECT COUNT(*) FROM timetable WHERE user_id = ?", user.id!!))
            },
        )
    }

    /** 같은 학기 시간표 [requests]개를 각각 대표로 동시에 지정한다. 대표 시간표는 하나다. */
    fun setPrimaryOnEveryTimetable(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val timetables = (0 until requests).map { fixtures.timetable(user, autumn, title = "시간표 $it", isPrimary = false) }
        return scenario(
            requests,
            request = { actions.setPrimary(user.id!!, timetables[it].id!!) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(1, count("SELECT COUNT(*) FROM timetable WHERE user_id = ? AND is_primary", user.id!!))
            },
        )
    }

    // ---- 강의평 (course, evaluation 행 PESSIMISTIC_WRITE) ----

    private class EvaluationTarget(
        val course: Course,
        val lecture: Lecture,
    )

    private fun evaluationTarget(): EvaluationTarget {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val courseRepository: CourseRepository = beans.getBean()
        val course = courseRepository.save(Course(courseNumber = "4190.310", instructor = "김교수", title = "운영체제"))
        val lecture = fixtures.lecture(autumn, courseNumber = course.courseNumber, courseTitle = course.title) { courseId = course.id }
        return EvaluationTarget(course, lecture)
    }

    private fun writeRequest(rating: Double) =
        EvaluationWriteRequest(
            content = "과제가 많지만 배운 것이 많은 강의였습니다. 추천합니다.",
            gradeSatisfaction = rating,
            teachingSkill = rating,
            gains = rating,
            lifeBalance = rating,
            rating = rating,
        )

    private fun savedEvaluation(
        target: EvaluationTarget,
        userId: Long,
        rating: Double,
    ): Evaluation =
        beans.getBean<EvaluationRepository>().save(
            Evaluation(
                courseId = target.course.id!!,
                userId = userId,
                year = target.lecture.year,
                semester = target.lecture.semester,
                content = "기존에 작성된 강의평입니다. 내용은 스무 자를 넘깁니다.",
                gradeSatisfaction = rating,
                teachingSkill = rating,
                gains = rating,
                lifeBalance = rating,
                rating = rating,
            ),
        )

    private fun assertCourseAggregateMatchesVisibleEvaluations(courseId: Long) {
        val course = beans.getBean<CourseRepository>().findById(courseId).get()
        val visible = count("SELECT COUNT(*) FROM evaluation WHERE course_id = ? AND NOT is_hidden", courseId)
        val avg =
            jdbc.queryForObject(
                "SELECT AVG(rating) FROM evaluation WHERE course_id = ? AND NOT is_hidden",
                Double::class.java,
                courseId,
            )
        assertEquals(visible.toLong(), course.evalCount)
        assertEquals(avg, course.avgRating)
    }

    /** 같은 사용자가 같은 강의에 강의평을 [requests]번 동시에 쓴다. 하나만 저장되고 course 집계도 1개다. */
    fun sameUserEvaluatesTwice(requests: Int): ConcurrencyScenario {
        val target = evaluationTarget()
        val user = fixtures.user()
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { actions.createEvaluation(user.id!!, target.lecture.id!!, writeRequest(4.0)) },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.DUPLICATE_EVALUATION)
                assertEquals(1, count("SELECT COUNT(*) FROM evaluation WHERE course_id = ?", target.course.id!!))
                assertCourseAggregateMatchesVisibleEvaluations(target.course.id!!)
            },
        )
    }

    /** 서로 다른 사용자 [requests]명이 같은 과목에 동시에 강의평을 쓴다. course 집계(개수, 평균)가 실제 강의평과 같다. */
    fun differentUsersEvaluateOneCourse(requests: Int): ConcurrencyScenario {
        val target = evaluationTarget()
        val users = (0 until requests).map { fixtures.user() }
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { actions.createEvaluation(users[it].id!!, target.lecture.id!!, writeRequest(1.0 + it % 5)) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(requests, count("SELECT COUNT(*) FROM evaluation WHERE course_id = ?", target.course.id!!))
                assertCourseAggregateMatchesVisibleEvaluations(target.course.id!!)
            },
        )
    }

    /** 짝수 요청은 자기 기존 강의평을 지우고 홀수 요청은 새로 쓴다. 모두 끝난 뒤 course 집계가 보이는 강의평과 같다. */
    fun evaluationsWrittenAndDeletedTogether(requests: Int): ConcurrencyScenario {
        val target = evaluationTarget()
        val users = (0 until requests).map { fixtures.user() }
        val existing = users.withIndex().filter { it.index % 2 == 0 }.associate { it.index to savedEvaluation(target, it.value.id!!, 5.0) }
        beans.getBean<CourseAggregateUpdater>().update(target.course.id!!)
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { index ->
                val evaluation = existing[index]
                if (evaluation != null) {
                    actions.deleteEvaluation(users[index].id!!, evaluation.id!!)
                } else {
                    actions.createEvaluation(users[index].id!!, target.lecture.id!!, writeRequest(1.0 + index % 4))
                }
            },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(
                    requests / 2,
                    count("SELECT COUNT(*) FROM evaluation WHERE course_id = ? AND NOT is_hidden", target.course.id!!),
                )
                assertCourseAggregateMatchesVisibleEvaluations(target.course.id!!)
            },
        )
    }

    private fun likeTarget(): Evaluation = savedEvaluation(evaluationTarget(), fixtures.user().id!!, 4.0)

    private fun assertLikeCountMatchesLikes(evaluationId: Long) {
        val likeCount = jdbc.queryForObject("SELECT like_count FROM evaluation WHERE id = ?", Long::class.java, evaluationId)
        assertEquals(count("SELECT COUNT(*) FROM evaluation_like WHERE evaluation_id = ?", evaluationId).toLong(), likeCount)
    }

    /** 같은 사용자가 같은 강의평에 공감을 [requests]번 동시에 누른다. 공감은 1개, like_count도 1이다. */
    fun sameUserLikesTwice(requests: Int): ConcurrencyScenario {
        val evaluation = likeTarget()
        val user = fixtures.user()
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { actions.likeEvaluation(user.id!!, evaluation.id!!) },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.DUPLICATE_EVALUATION_LIKE)
                assertEquals(1, count("SELECT COUNT(*) FROM evaluation_like WHERE evaluation_id = ?", evaluation.id!!))
                assertLikeCountMatchesLikes(evaluation.id!!)
            },
        )
    }

    /** 짝수 요청은 이미 누른 공감을 취소하고 홀수 요청은 새로 누른다. like_count가 evaluation_like 행 수와 같다. */
    fun likesAndCancelsTogether(requests: Int): ConcurrencyScenario {
        val evaluation = likeTarget()
        val users = (0 until requests).map { fixtures.user() }
        val likeRepository: EvaluationLikeRepository = beans.getBean()
        users.filterIndexed { index, _ -> index % 2 == 0 }.forEach { likeRepository.save(EvaluationLike(evaluation.id!!, it.id!!)) }
        jdbc.update("UPDATE evaluation SET like_count = ? WHERE id = ?", (requests + 1) / 2, evaluation.id!!)
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { index ->
                if (index % 2 == 0) {
                    actions.cancelLikeEvaluation(users[index].id!!, evaluation.id!!)
                } else {
                    actions.likeEvaluation(users[index].id!!, evaluation.id!!)
                }
            },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(requests / 2, count("SELECT COUNT(*) FROM evaluation_like WHERE evaluation_id = ?", evaluation.id!!))
                assertLikeCountMatchesLikes(evaluation.id!!)
            },
        )
    }

    // ---- 계정 (unique 제약, user 행 PESSIMISTIC_WRITE) ----

    /** 같은 localId로 [requests]번 동시에 가입한다. 하나만 가입되고 나머지는 localId 중복이다. */
    fun signUpWithSameLocalId(requests: Int): ConcurrencyScenario =
        scenario(
            requests,
            request = { actions.registerLocal("racer", "password1", "racer$it@snu.ac.kr") },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.DUPLICATE_LOCAL_ID)
                assertEquals(1, count("SELECT COUNT(*) FROM `user` WHERE local_id = 'racer'"))
            },
        )

    /** 같은 강의를 [requests]번 동시에 관심강좌에 넣는다. 모두 성공(멱등)하고 행은 하나다. */
    fun bookmarkSameLecture(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val lecture = fixtures.lecture(autumn)
        return scenario(
            requests,
            request = { actions.bookmarkLecture(user.id!!, lecture.id!!) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(1, count("SELECT COUNT(*) FROM bookmark_lecture WHERE user_id = ?", user.id!!))
            },
        )
    }

    /** 같은 강의의 강의 일기를 [requests]번 동시에 제출한다. 하나만 저장되고 나머지는 제출 간격 위반이다. */
    fun submitSameDiary(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val lecture = fixtures.lecture(autumn)
        return scenario(
            requests,
            request = { actions.submitEmptyDiary(user.id!!, lecture.id!!) },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.DIARY_SUBMISSION_TOO_FREQUENT)
                assertEquals(1, count("SELECT COUNT(*) FROM diary_submission WHERE user_id = ?", user.id!!))
            },
        )
    }

    /** 기본 테마 지정을 [requests]번 동시에 보낸다(설정 행이 아직 없다). 모두 성공하고 설정 행은 하나다. */
    fun setDefaultThemeTwice(requests: Int): ConcurrencyScenario {
        val user = fixtures.user()
        val service: TimetableThemeService = beans.getBean()
        val fall = service.builtinThemeId("fall")
        return scenario(
            requests,
            request = { actions.setDefaultTheme(user.id!!, fall) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(1, count("SELECT COUNT(*) FROM user_preference WHERE user_id = ?", user.id!!))
            },
        )
    }

    /** 기본 테마를 지정하는 요청(요청 0)과 기본 테마 해제 요청이 겹친다. 해제는 지정된 결과를 보고 판단하므로 NOT_DEFAULT_THEME이다. */
    fun setAndUnsetDefaultThemeTogether(requests: Int): ConcurrencyScenario {
        val user = fixtures.user()
        val service: TimetableThemeService = beans.getBean()
        val fall = service.builtinThemeId("fall")
        val snutt = service.builtinThemeId("snutt")
        return scenario(
            requests,
            request = { if (it == 0) actions.setDefaultTheme(user.id!!, fall) else actions.unsetDefaultTheme(user.id!!, snutt) },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.NOT_DEFAULT_THEME)
                assertEquals(fall, service.getDefaultThemeId(user.id!!))
            },
            // 순서를 정하지 않으면 지정보다 먼저 잠근 해제는 성공한다(아직 기본이 snutt다). 지정은 항상 성공하고, 지정 뒤의 해제는 거절되므로
            // 최종 기본 테마는 fall이다.
            verifyUnordered = { outcome ->
                assertTrue(outcome.results[0].isSuccess) { "기본 테마 지정이 실패했다: ${outcome.results[0]}" }
                assertTrue(outcome.errorTypes().all { it == ErrorType.NOT_DEFAULT_THEME }) { "해제 실패 사유: ${outcome.errorTypes()}" }
                assertEquals(fall, service.getDefaultThemeId(user.id!!))
                assertEquals(1, count("SELECT COUNT(*) FROM user_preference WHERE user_id = ?", user.id!!))
            },
        )
    }

    /** 내 테마를 기본으로 지정하는 요청(요청 0)과 그 테마를 지우는 요청이 겹친다. 삭제는 기본 설정을 기본 테마로 되돌린다. */
    fun setDefaultThemeWhileDeletingIt(requests: Int): ConcurrencyScenario {
        val user = fixtures.user()
        val service: TimetableThemeService = beans.getBean()
        val theme = service.addTheme(user.id!!, "지울 테마", listOf(ColorSet("#000001", "#ffffff")))
        return scenario(
            requests,
            request = { if (it == 0) actions.setDefaultTheme(user.id!!, theme.id) else actions.deleteTheme(user.id!!, theme.id) },
            verify = { outcome ->
                outcome.assertOutcome(successes = 2, ErrorType.THEME_NOT_FOUND)
                assertEquals(0, count("SELECT COUNT(*) FROM timetable_theme WHERE id = ?", theme.id))
                assertEquals(service.builtinThemeId("snutt"), service.getDefaultThemeId(user.id!!))
            },
            // 순서를 정하지 않으면 지정이 삭제 뒤에 잠글 수 있다. 그때 지정은 THEME_NOT_FOUND다. 삭제는 정확히 한 번 성공하고,
            // 어느 순서든 지워진 테마가 기본으로 남지 않는다.
            verifyUnordered = { outcome ->
                val types = outcome.errorTypes()
                assertTrue(types.all { it == ErrorType.THEME_NOT_FOUND }) { "실패 사유: $types" }
                assertEquals(1, outcome.results.drop(1).count { it.isSuccess })
                assertEquals(0, count("SELECT COUNT(*) FROM timetable_theme WHERE id = ?", theme.id))
                assertEquals(service.builtinThemeId("snutt"), service.getDefaultThemeId(user.id!!))
            },
        )
    }

    /** 같은 푸시 설정 저장을 [requests]번 동시에 보낸다(설정 행이 아직 없다). 모두 성공하고 종류마다 행은 하나다. */
    fun savePushPreferencesTwice(requests: Int): ConcurrencyScenario {
        val user = fixtures.user()
        val dto = PushPreferenceDto(listOf(PushPreferenceItem(PushPreferenceType.NORMAL, isEnabled = false)))
        return scenario(
            requests,
            request = { actions.savePushPreferences(user.id!!, dto) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(1, count("SELECT COUNT(*) FROM push_preference WHERE user_id = ?", user.id!!))
            },
        )
    }

    /** 같은 FCM 등록 토큰을 [requests]번 동시에 등록한다(앱 시작 시 중복 호출). 모두 성공하고 활성 기기는 하나다. */
    fun registerSameDeviceTwice(requests: Int): ConcurrencyScenario {
        val user = fixtures.user()
        val clientInfo = ClientInfo(osType = OsType.IOS, deviceId = "device-1")
        return scenario(
            requests,
            request = { actions.addRegistrationId(user.id!!, "fcm-token-1", clientInfo) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(1, count("SELECT COUNT(*) FROM user_device WHERE user_id = ? AND NOT is_deleted", user.id!!))
            },
        )
    }

    /**
     * 서로 다른 사용자 [requests]명이 같은 FCM 토큰을 동시에 등록한다(한 기기에서 계정을 바꿔 로그인).
     * 토큰은 기기 하나를 가리키므로 모두 끝난 뒤 그 토큰의 활성 기기는 하나여야 하고, 요청은 오류 없이 끝나야 한다.
     */
    fun registerSameTokenFromDifferentUsers(requests: Int): ConcurrencyScenario {
        val users = (0 until requests).map { fixtures.user() }
        return scenario(
            requests,
            request = {
                actions.addRegistrationId(
                    users[it].id!!,
                    "fcm-shared-token",
                    ClientInfo(osType = OsType.ANDROID, deviceId = "phone"),
                )
            },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(
                    1,
                    count("SELECT COUNT(*) FROM user_device WHERE fcm_registration_id = 'fcm-shared-token' AND NOT is_deleted"),
                )
            },
        )
    }

    /**
     * 사용자 [requests]명이 같은 닉네임으로 동시에 바꾼다. 태그는 남은 태그 중 무작위로 고르므로 동시 요청끼리 같은 태그를 고르면
     * unique 제약에 걸려 DUPLICATE_NICKNAME이 된다(남은 태그가 있어도). 500 없이 끝나고, 성공한 사용자의 태그는 서로 달라야 한다.
     */
    fun sameNicknameConcurrently(requests: Int): ConcurrencyScenario {
        val users = (0 until requests).map { fixtures.user() }
        return scenario(
            requests,
            request = { actions.updateNickname(users[it].id!!, "동시닉네임") },
            verify = { outcome ->
                val types = outcome.errorTypes()
                assertTrue(types.all { it == ErrorType.DUPLICATE_NICKNAME }) { "실패 사유: $types" }
                assertEquals(outcome.successes.size, count("SELECT COUNT(*) FROM `user` WHERE nickname = '동시닉네임' AND active"))
                assertEquals(
                    outcome.successes.size,
                    count("SELECT COUNT(DISTINCT nickname_tag) FROM `user` WHERE nickname = '동시닉네임' AND active"),
                )
            },
        )
    }

    /**
     * 리마인더가 없는 강의에 같은 리마인더 설정을 [requests]번 동시에 보낸다(설정 화면 중복 탭). 모두 성공하고 리마인더 행은 하나,
     * 발송 시각도 수업 시간 하나만큼이어야 한다.
     */
    fun setReminderOnSameLecture(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val timetable = fixtures.timetable(user, autumn)
        val entry = fixtures.addLecture(timetable, fixtures.lecture(autumn, times = listOf(mondayMorning)))
        return scenario(
            requests,
            request = { actions.modifyReminder(user.id!!, timetable.id!!, entry.id!!, TimetableLectureReminderOption.TEN_MINUTES_BEFORE) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(1, count("SELECT COUNT(*) FROM timetable_lecture_reminder WHERE timetable_lecture_id = ?", entry.id!!))
                assertEquals(1, count("SELECT COUNT(*) FROM timetable_lecture_reminder_schedule"))
            },
        )
    }
}
