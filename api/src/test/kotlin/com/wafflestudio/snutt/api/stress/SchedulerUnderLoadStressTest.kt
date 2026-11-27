package com.wafflestudio.snutt.api.stress

import com.wafflestudio.snutt.api.scheduler.DiaryScheduler
import com.wafflestudio.snutt.api.scheduler.MailLogCleanupScheduler
import com.wafflestudio.snutt.api.scheduler.ReminderScheduler
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.ErrorType.INVALID_CLASS_TIME
import com.wafflestudio.snutt.core.common.error.ErrorType.LECTURE_TIME_OVERLAP
import com.wafflestudio.snutt.core.common.error.ErrorType.TIMETABLE_LECTURE_NOT_FOUND
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.test.StressTest
import com.wafflestudio.snutt.core.test.concurrency.runConcurrently
import com.wafflestudio.snutt.core.test.stress.DomainInvariants
import com.wafflestudio.snutt.core.test.stress.FailureKind
import com.wafflestudio.snutt.core.test.stress.MySqlProbe
import com.wafflestudio.snutt.core.test.stress.StressRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * `api/.../scheduler/Schedulers.kt`의 스케줄 작업이 도는 중에 API 부하를 건다. 테스트 컨텍스트는 `@Scheduled`가 꺼져 있으므로
 * 스케줄 진입점(락·시계 포함)을 백그라운드 스레드에서 쉬지 않고 부른다: 리마인더 발송(매분),
 * 일기장 알림 예약(평일 06시)·발송(매분), 메일 로그 정리(하위 클래스에 따라 지난 학기 리마인더 정리도). 발송할 때마다 시계를 1분씩 옮긴다(60분 주기로 되감는다).
 *
 * 부하: 사용자마다 대표 시간표(2026 가을)에 월요일 10~12시 강의가 있고 지난 학기(2026 봄) 시간표에도 리마인더 강의가 있다.
 * 스레드마다 자기 강의 하나를 맡아 직접 만든 강의를 넣고 지우고, 강의 제목을 바꾸고, 시간표·리마인더를 읽는다.
 * [reminderEdits]면 리마인더 설정과 수업 시간 변경(리마인더 재계산)도 섞는다. 같은 강의의 리마인더를 동시에 바꾸는 race는
 * [KnownFailureRaceStressTest]에 따로 있다.
 *
 * 반복마다 리마인더 발송 시각 = 실제 수업 시간 + offset, 수업 시간 없는 리마인더 없음, 정리 뒤 지난 학기 리마인더 없음,
 * [DomainInvariants], 스케줄러 예외·5xx·데드락을 본다.
 */
abstract class AbstractSchedulerUnderLoadStressTest(
    private val scenarioName: String,
    /** true면 리마인더 설정·수업 시간 변경(발송 시각 delete+insert)을 섞는다. */
    private val reminderEdits: Boolean,
    /** true면 지난 학기 리마인더 정리(매일 04시)도 부하 중에 돌린다. false면 부하가 끝난 뒤에만 돌린다. */
    private val concurrentCleanup: Boolean,
) : AbstractStressTest() {
    private val invariants by lazy { DomainInvariants(applicationContext) }

    private class Owned(
        val userId: Long,
        val timetableId: Long,
        val entryId: Long,
    )

    @Test
    fun `리마인더·일기장·정리 스케줄러가 도는 중에 시간표와 리마인더를 고친다`() =
        stress(scenarioName) {
            for (iteration in 1..settings.iterations) {
                if (shouldStop()) break
                iteration(iteration) {
                    resetForIteration()
                    clock.setKst(BASE_TIME)
                    val owned = prepare()
                    val stop = AtomicBoolean(false)
                    val fires = AtomicInteger()
                    val scheduler = Executors.newSingleThreadExecutor()
                    val schedulerTask = scheduler.submit { runSchedulers(this, stop, fires) }
                    try {
                        runConcurrently(settings.threads, LOAD_TIMEOUT) { thread ->
                            val random = Random(settings.seed * 17 + iteration * 7_919L + thread)
                            repeat(settings.opsPerThread) { operate(this, owned[thread], random) }
                        }
                    } catch (e: AssertionError) {
                        fail(FailureKind.CLIENT_TIMEOUT, "load", e.message.orEmpty())
                    } finally {
                        stop.set(true)
                        schedulerTask.get(LOAD_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                        scheduler.shutdownNow()
                    }
                    // 부하가 끝난 뒤 정리를 한 번 더 돌리면 지난 학기 리마인더는 남지 않아야 한다.
                    applicationContext.getBean<ReminderScheduler>().deletePastSemesterReminders()
                    verifyPastSemesterRemindersDeleted()
                    // 발송 창(09:50~10:50)에 10시대 수업의 10분 전 알림이 들어오므로 부하 중에 리마인더 알림이 실제로 나갔어야 한다.
                    val sent = MySqlProbe.jdbc.queryForObject("SELECT COUNT(*) FROM notification", Int::class.java)!!
                    if (sent == 0) throw AssertionError("부하 중에 리마인더 알림이 한 번도 나가지 않았다(스케줄러가 실제로 겹치지 않았다)")
                    invariants.assertHolds()
                }
            }
        }

    /** 스레드 4개당 사용자 1명. 스레드마다 강의 하나(지난 학기 것도 섞는다)를 맡고, 처음에는 모두 리마인더가 켜져 있다. */
    private fun prepare(): List<Owned> {
        val autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        val spring = fixtures.coursebook(2026, Semester.SPRING)
        val reminderService: TimetableLectureReminderService = applicationContext.getBean()
        val users = (0 until (settings.threads + THREADS_PER_USER - 1) / THREADS_PER_USER).map { fixtures.user() }
        return users.flatMap { user ->
            val current = fixtures.timetable(user, autumn)
            val past = fixtures.timetable(user, spring, title = "지난 학기")
            (0 until THREADS_PER_USER).map { slot ->
                val (coursebook, timetable) = if (slot == THREADS_PER_USER - 1) spring to past else autumn to current
                // 월요일 10:00부터 30분 간격, 25분 수업. 같은 시간표 안에서 서로 겹치지 않는다.
                val start = 600 + slot * 30
                val lecture =
                    fixtures.lecture(
                        coursebook,
                        times =
                            listOf(
                                ClassPlaceAndTime(
                                    DayOfWeek.MONDAY,
                                    "301-1$slot",
                                    start,
                                    start + 25,
                                ),
                            ),
                    )
                val entry = fixtures.addLecture(timetable, lecture)
                reminderService.modifyReminder(user.id!!, timetable.id!!, entry.id!!, TimetableLectureReminderOption.TEN_MINUTES_BEFORE)
                Owned(user.id!!, timetable.id!!, entry.id!!)
            }
        }
    }

    /** 운영 스케줄 진입점을 부하가 끝날 때까지 반복한다. 예외는 스케줄러 실패로 센다(운영에서는 로그만 남고 다음 주기를 기다린다). */
    private fun runSchedulers(
        run: StressRun,
        stop: AtomicBoolean,
        fires: AtomicInteger,
    ) {
        val reminder: ReminderScheduler = applicationContext.getBean()
        val diary: DiaryScheduler = applicationContext.getBean()
        val mailLog: MailLogCleanupScheduler = applicationContext.getBean()
        val jobs =
            listOf(
                "fireDueReminders" to { reminder.fireDueReminders() },
                "planDiaryNotifications" to { diary.planDiaryNotificationsScheduled() },
                "sendDueDiaryNotifications" to { diary.sendDueDiaryNotificationsScheduled() },
                "deleteExpiredMailLogs" to { mailLog.deleteExpiredMailLogs() },
            )
        val cleanup = listOf("deletePastSemesterReminders" to { reminder.deletePastSemesterReminders() })
        val scheduled = if (concurrentCleanup) jobs + cleanup else jobs
        while (!stop.get()) {
            val minute = fires.getAndIncrement()
            clock.setKst(BASE_TIME.plusMinutes((minute % 60).toLong()))
            scheduled.forEach { (name, job) ->
                try {
                    job()
                    run.record("scheduler:$name", "ok", allowed = true)
                } catch (e: Exception) {
                    run.record("scheduler:$name", FailureKind.classify(e).name, allowed = false, detail = FailureKind.summarize(e))
                }
            }
        }
    }

    private fun operate(
        run: StressRun,
        owned: Owned,
        random: Random,
    ) {
        val token = token(owned.userId)
        val base = "/v2/timetables/${owned.timetableId}/lectures"

        fun call(
            operation: String,
            method: String,
            path: String,
            body: Any?,
            vararg allowed: ErrorType,
        ): StressHttp.Response {
            val response = http.send(method, path, token, body)
            run.record(
                operation,
                response.outcome,
                response.isSuccess || response.errorType in allowed,
                "$method $path -> ${response.status} ${response.body.take(300)}",
            )
            return response
        }

        when (if (reminderEdits) random.nextInt(4) else 2 + random.nextInt(4)) {
            0 ->
                call(
                    "modifyReminder",
                    "PUT",
                    "$base/${owned.entryId}/reminder",
                    mapOf(
                        "option" to TimetableLectureReminderOption.entries.random(random),
                    ),
                )
            1 -> {
                // 맡은 강의의 수업 시간을 같은 30분 칸 안에서 옮긴다(다른 스레드의 강의와 겹치지 않는다). 리마인더가 재계산된다.
                val slotStart =
                    MySqlProbe.jdbc.queryForObject(
                        "SELECT MIN(c.start_minute) FROM timetable_lecture tl JOIN lecture_class_time c ON c.lecture_id = tl.lecture_id WHERE tl.id = ?",
                        Int::class.java,
                        owned.entryId,
                    )!!
                val start = slotStart + random.nextInt(4)
                val time = ClassPlaceAndTime(DayOfWeek.MONDAY, "변경", start, start + 20)
                call(
                    "modifyLectureTime",
                    "PATCH",
                    "$base/${owned.entryId}",
                    mapOf("classPlaceAndTimes" to listOf(time)),
                    INVALID_CLASS_TIME,
                )
            }
            2 -> {
                // 오후 칸에 직접 만든 강의를 넣었다가 지운다. 같은 사용자의 스레드끼리 겹치면 시간 겹침으로 거절된다.
                val start = 840 + random.nextInt(8) * 30
                val time = ClassPlaceAndTime(DayOfWeek.entries[random.nextInt(5)], "직접", start, start + 25)
                val title = "잠깐 ${random.nextLong()}"
                val added =
                    call(
                        "addCustomLecture",
                        "POST",
                        "$base/custom",
                        mapOf("courseTitle" to title, "classPlaceAndTimes" to listOf(time)),
                        LECTURE_TIME_OVERLAP,
                    )
                if (added.isSuccess) {
                    val customId = http.json(added.body)["lectures"].first { it["courseTitle"].asString() == title }["id"].asLong()
                    call("deleteCustomLecture", "DELETE", "$base/$customId", null, TIMETABLE_LECTURE_NOT_FOUND)
                }
            }
            3 -> call("getReminders", "GET", "$base/reminders", null)
            4 ->
                // 리마인더가 걸린 강의의 제목만 바꾼다. 발송 시각은 그대로고, 발송 문구가 읽는 override가 바뀐다.
                call("modifyLectureTitle", "PATCH", "$base/${owned.entryId}", mapOf("courseTitle" to "제목 ${random.nextInt(100)}"))
            else -> call("getTimetable", "GET", "/v2/timetables/${owned.timetableId}", null)
        }
    }

    /** 정리를 마지막으로 돌렸으므로 지난 학기 리마인더는 남지 않는다. 발송 시각 일관성은 [DomainInvariants.reminderViolations]가 본다. */
    private fun verifyPastSemesterRemindersDeleted() {
        val remaining =
            MySqlProbe.jdbc.queryForList(
                "SELECT r.id FROM timetable_lecture_reminder r JOIN timetable_lecture tl ON tl.id = r.timetable_lecture_id " +
                    "JOIN timetable t ON t.id = tl.timetable_id WHERE t.year < 2026 OR (t.year = 2026 AND t.semester < 3)",
                Long::class.java,
            )
        if (remaining.isNotEmpty()) throw AssertionError("정리 뒤에도 남은 지난 학기 리마인더: $remaining")
    }

    private companion object {
        const val THREADS_PER_USER = 4

        /** 2026 가을 학기 월요일 09:50 KST. 리마인더 발송 창(10분)에 10시 수업의 10분 전 알림이 걸린다. */
        val BASE_TIME: LocalDateTime = LocalDateTime.of(2026, 9, 14, 9, 50)
        val LOAD_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}

/**
 * 리마인더 발송·일기장 예약/발송·메일 로그 정리가 도는 중의 부하. 리마인더 발송 시각은 바꾸지 않고, 지난 학기 정리는 부하가 끝난 뒤 돌린다.
 * 통과해야 한다. 나머지 두 조합은 재현된 버그라 known-failure로 둔다.
 */
@StressTest
class SchedulerUnderLoadStressTest :
    AbstractSchedulerUnderLoadStressTest("scheduler-under-load", reminderEdits = false, concurrentCleanup = false)
