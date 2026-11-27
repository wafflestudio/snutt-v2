package com.wafflestudio.snutt.api.concurrency

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkService
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.Evaluation
import com.wafflestudio.snutt.core.domain.evaluation.model.EvaluationLike
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationLikeRepository
import com.wafflestudio.snutt.core.domain.evaluation.repository.EvaluationRepository
import com.wafflestudio.snutt.core.domain.evaluation.service.CourseAggregateUpdater
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationWriteRequest
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.service.CustomTimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
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
    ): ConcurrencyScenario = LambdaScenario(requests, isolation, request, verify)

    private class LambdaScenario(
        override val requests: Int,
        override val isolation: Int,
        private val onRequest: (Int) -> Unit,
        private val onVerify: (ConcurrentOutcome<Unit>) -> Unit,
    ) : ConcurrencyScenario {
        override fun request(index: Int) = onRequest(index)

        override fun verify(outcome: ConcurrentOutcome<Unit>) = onVerify(outcome)
    }

    // ---- 시간표 (timetable 행 PESSIMISTIC_WRITE) ----

    /** 같은 시간표에 서로 시간이 겹치는 강의 [requests]개를 동시에 넣는다. 하나만 들어가고 나머지는 시간 겹침이다. */
    fun overlappingLecturesIntoOneTimetable(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val timetable = fixtures.timetable(user, autumn)
        val lectures = (0 until requests).map { fixtures.lecture(autumn, times = listOf(mondayMorning)) }
        val service: TimetableLectureService = beans.getBean()
        return scenario(
            requests,
            request = { service.addLecture(user.id!!, timetable.id!!, TimetableLectureAddRequest(lectures[it].id!!)) },
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
        val service: TimetableLectureService = beans.getBean()
        return scenario(
            requests,
            request = { service.addLecture(user.id!!, timetable.id!!, TimetableLectureAddRequest(lecture.id!!)) },
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
        val lectureService: TimetableLectureService = beans.getBean()
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
                    themeService.modifyTheme(user.id!!, theme.id, name = null, colors = nineColors.take(2))
                } else {
                    val time = ClassPlaceAndTime(DayOfWeek.entries[index % 5], "301-10$index", 60 * index, 60 * index + 50)
                    lectureService.addCustomLecture(
                        user.id!!,
                        timetable.id!!,
                        CustomTimetableLectureAddRequest(courseTitle = "직접 추가 $index", classPlaceAndTimes = listOf(time)),
                    )
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
        val service: TimetableService = beans.getBean()
        return scenario(
            requests,
            request = { service.addTimetable(user.id!!, autumn.year, autumn.semester, "시간표 $it") },
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
        val service: TimetableService = beans.getBean()
        return scenario(
            requests,
            request = { service.addTimetable(user.id!!, autumn.year, autumn.semester, "같은 제목") },
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
        val service: TimetableService = beans.getBean()
        return scenario(
            requests,
            request = { service.deleteTimetable(user.id!!, timetables[it].id!!) },
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
        val service: TimetableService = beans.getBean()
        return scenario(
            requests,
            request = { service.setPrimary(user.id!!, timetables[it].id!!) },
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
        val service: EvaluationService = beans.getBean()
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { service.createEvaluation(user.id!!, target.lecture.id!!, writeRequest(4.0)) },
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
        val service: EvaluationService = beans.getBean()
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { service.createEvaluation(users[it].id!!, target.lecture.id!!, writeRequest(1.0 + it % 5)) },
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
        val service: EvaluationService = beans.getBean()
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { index ->
                val evaluation = existing[index]
                if (evaluation != null) {
                    service.deleteEvaluation(users[index].id!!, evaluation.id!!)
                } else {
                    service.createEvaluation(users[index].id!!, target.lecture.id!!, writeRequest(1.0 + index % 4))
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
        val service: EvaluationService = beans.getBean()
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { service.likeEvaluation(user.id!!, evaluation.id!!) },
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
        val service: EvaluationService = beans.getBean()
        return scenario(
            requests,
            isolation = TransactionDefinition.ISOLATION_READ_COMMITTED,
            request = { index ->
                if (index % 2 == 0) {
                    service.cancelLikeEvaluation(users[index].id!!, evaluation.id!!)
                } else {
                    service.likeEvaluation(users[index].id!!, evaluation.id!!)
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
    fun signUpWithSameLocalId(requests: Int): ConcurrencyScenario {
        val service: AuthService = beans.getBean()
        return scenario(
            requests,
            request = { service.registerLocal("racer", "password1", "racer$it@snu.ac.kr") },
            verify = { outcome ->
                outcome.assertOutcome(successes = 1, ErrorType.DUPLICATE_LOCAL_ID)
                assertEquals(1, count("SELECT COUNT(*) FROM `user` WHERE local_id = 'racer'"))
            },
        )
    }

    /** 같은 강의를 [requests]번 동시에 관심강좌에 넣는다. 모두 성공(멱등)하고 행은 하나다. */
    fun bookmarkSameLecture(requests: Int): ConcurrencyScenario {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val user = fixtures.user()
        val lecture = fixtures.lecture(autumn)
        val service: BookmarkService = beans.getBean()
        return scenario(
            requests,
            request = { service.addLecture(user.id!!, lecture.id!!) },
            verify = { outcome ->
                outcome.assertAllSucceeded()
                assertEquals(1, count("SELECT COUNT(*) FROM bookmark_lecture WHERE user_id = ?", user.id!!))
            },
        )
    }
}
