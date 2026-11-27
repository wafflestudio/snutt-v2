package com.wafflestudio.snutt.batch.stress

import com.wafflestudio.snutt.batch.SugangXlsxFixture.RowData
import com.wafflestudio.snutt.batch.sugang.SugangSyncScenarioTest
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureModifyRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.test.StressTest
import com.wafflestudio.snutt.core.test.concurrency.runConcurrently
import com.wafflestudio.snutt.core.test.fault.DbFaults
import com.wafflestudio.snutt.core.test.stress.DomainInvariants
import com.wafflestudio.snutt.core.test.stress.FailureKind
import com.wafflestudio.snutt.core.test.stress.MySqlProbe
import com.wafflestudio.snutt.core.test.stress.ServerErrorLog
import com.wafflestudio.snutt.core.test.stress.StressRun
import com.wafflestudio.snutt.core.test.stress.StressSettings
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.random.Random

/**
 * 수강편람 동기화 잡([com.wafflestudio.snutt.batch.sugangsnu.SugangSnuSyncJob])이 도는 동안 사용자들이 시간표를 고친다.
 * batch 모듈에는 웹 서버가 없으므로 사용자 요청은 컨트롤러가 부르는 서비스(`TimetableLectureService`, 리마인더 서비스)를 스레드에서 바로 부른다.
 *
 * 반복마다: 강의 [LECTURES]개로 첫 동기화 → 사용자마다 겹치지 않는 강의 몇 개를 담고 리마인더를 건다 → 엑셀을 바꿔(수업 시간 변경 30%,
 * 폐강 10%, 신설 10%, 제목 변경) 잡을 백그라운드에서 돌리는 동안 스레드들이 하위 클래스가 정한 요청을 잡이 끝날 때까지 보낸다.
 * 끝나면 잡이 성공했는지, 시간표 시간 겹침 없음·리마인더 발송 시각 = 수업 시간 + offset·고아 행 없음([DomainInvariants])을 본다.
 *
 * 동기화는 강의 반영 → 시간표 반영(시간표를 id 순으로 잠근 뒤 다시 읽음) → 폐강 강의 삭제를 따로 커밋한다. 사용자 요청과 락 순서가 같아야
 * 데드락이 나지 않고, 잠근 뒤의 상태를 다시 읽어야 겹침·리마인더 어긋남·외래 키 위반이 나지 않는다.
 */
abstract class AbstractSugangSyncUnderLoadStressTest(
    private val scenarioName: String,
    /** 사용자 요청 구성. [SAFE_OPERATIONS], [ADD_OPERATION], [COPY_OPERATION], [EDIT_OPERATIONS], [REMINDER_OPERATIONS] 중에서 고른다. */
    private val operations: List<String>,
    /**
     * 켜면 부하 중 동기화의 시간표 반영 묶음과 알림 배출을 DB 트리거로 실패시키고([DbFaults]), 부하가 끝난 뒤 장애 없이 한 번 더 돌린다.
     * 두 번째 실행이 남은 일을 마쳐 불변식이 성립하고, 남은 기록이 없고, 같은 알림이 두 번 남지 않아야 한다.
     */
    private val injectFaults: Boolean = false,
) : SugangSyncScenarioTest() {
    @Autowired lateinit var applicationContext: ApplicationContext

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var timetableService: TimetableService

    private val settings = StressSettings.fromSystemProperties()
    private val invariants by lazy { DomainInvariants(applicationContext) }

    private class Owner(
        val user: User,
        val timetable: Timetable,
    )

    @Test
    fun `수강편람 동기화 중에 사용자들이 시간표를 고친다`() {
        ServerErrorLog().use { serverErrors ->
            val run = StressRun(scenarioName, settings, MySqlProbe.environment(dataSource), serverErrors)
            for (iteration in 1..settings.iterations) {
                if (run.shouldStop()) break
                run.iteration(iteration) { runIteration(run, iteration, serverErrors) }
            }
            run.finish()
        }
    }

    private fun runIteration(
        run: StressRun,
        iteration: Int,
        serverErrors: ServerErrorLog,
    ) {
        resetStores()
        val random = Random(settings.seed + iteration)
        val before = (0 until LECTURES).map { row(it, random) }
        sync(before)
        val lectures = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN)
        val owners = prepareOwners(lectures, random)

        val after = mutate(before, random)
        // 엑셀에서 한 글자도 바뀌지 않은 강의. 동기화가 이 강의를 담은 timetable_lecture는 건드리지 않는다.
        val unchanged = (before.toSet() intersect after.toSet()).map { it.courseNumber }.toSet()
        val stableLectureIds = lectures.filter { it.courseNumber in unchanged }.map { it.id!! }.toSet()
        stubSite(after)
        val faults =
            if (injectFaults) {
                listOf(
                    // 짝수 id 시간표가 든 반영 묶음이 처리 표시에서 실패한다(묶음 전체가 롤백된다).
                    DbFaults.failing("sugang_sync_pending_timetable", "UPDATE", "NEW.timetable_id % 2 = 0"),
                    // 알림 배출이 짝수 id outbox 행을 지우다 실패한다(옮긴 notification도 함께 롤백된다).
                    DbFaults.failing("sugang_sync_outbox", "DELETE", "OLD.id % 2 = 0"),
                )
            } else {
                emptyList()
            }
        val executor = Executors.newSingleThreadExecutor()
        val job = executor.submit<Throwable?> { runCatching { sugangSnuSyncJob.run(null) }.exceptionOrNull() }
        try {
            runConcurrently(settings.threads, LOAD_TIMEOUT) { thread ->
                val threadRandom = Random(settings.seed * 13 + iteration * 7_919L + thread)
                val owner = owners[thread % owners.size]
                var ops = 0
                // 잡이 끝날 때까지 계속 보내고, 잡이 빨리 끝나도 최소 opsPerThread개는 보낸다.
                while (!job.isDone || ops < settings.opsPerThread) {
                    operate(run, owner, lectures, stableLectureIds, threadRandom)
                    ops++
                }
            }
        } catch (e: AssertionError) {
            run.fail(FailureKind.CLIENT_TIMEOUT, "load", e.message.orEmpty())
            return
        } finally {
            val error = job.get(LOAD_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
            executor.shutdownNow()
            faults.forEach { it.close() }
            if (injectFaults) {
                // 주입한 장애로 잡이 실패하는 것은 정상이다. 그 밖의 서버 오류만 실패로 센다.
                run.record("sugangSnuSyncJob(faults)", error?.let { "failed" } ?: "ok", allowed = error == null || error.isInjected())
                serverErrors.drain().filterNot { it.error.isInjected() }.forEach { entry ->
                    run.fail(entry.kind, entry.logger.substringAfterLast('.'), FailureKind.summarize(entry.error))
                }
            } else if (error != null) {
                run.record("sugangSnuSyncJob", FailureKind.classify(error).name, allowed = false, detail = FailureKind.summarize(error))
            } else {
                run.record("sugangSnuSyncJob", "ok", allowed = true)
            }
        }
        if (injectFaults) {
            // 장애 없이 다시 돌린다. 엑셀은 그대로라 diff는 비고, 남은 시간표 반영과 알림을 마친다.
            sync(after)
            val leftovers =
                listOf("sugang_sync_pending_lecture", "sugang_sync_pending_timetable", "sugang_sync_outbox")
                    .associateWith { MySqlProbe.jdbc.queryForObject("SELECT COUNT(*) FROM $it", Long::class.java)!! }
                    .filterValues { it > 0 }
            if (leftovers.isNotEmpty()) throw AssertionError("다시 돌린 뒤에도 남은 기록이 있다: $leftovers")
            val duplicated =
                MySqlProbe.jdbc.queryForList(
                    "SELECT user_id, message, deeplink, COUNT(*) AS n FROM notification GROUP BY user_id, message, deeplink HAVING n > 1",
                )
            if (duplicated.isNotEmpty()) throw AssertionError("같은 알림이 두 번 이상 남았다: ${duplicated.take(5)}")
        }
        // 동기화가 반영됐는지(롤백되지 않았는지) 확인한다: 폐강 강의는 없고 신설 강의는 있다.
        val synced = lectureRepository.findByYearAndSemester(2026, Semester.AUTUMN).map { it.courseNumber }.toSet()
        val expected = after.map { it.courseNumber }.toSet()
        if (synced != expected) throw AssertionError("동기화 결과가 엑셀과 다르다: 없어야 할 ${synced - expected}, 있어야 할 ${expected - synced}")
        invariants.assertHolds()
    }

    /** 사용자마다 대표 시간표에 서로 겹치지 않는 강의를 실제 서비스로 담고, 담은 강의마다 리마인더를 건다. */
    private fun prepareOwners(
        lectures: List<Lecture>,
        random: Random,
    ): List<Owner> {
        val autumn = coursebookRepository.findAll().single()
        return (0 until (settings.threads / THREADS_PER_USER).coerceAtLeast(2)).map {
            val user = fixtures.user()
            val timetable = fixtures.timetable(user, autumn)
            lectures.shuffled(random).take(INITIAL_LECTURES_PER_USER).forEach { lecture ->
                val entryId =
                    runCatching { addLecture(user, timetable, lecture) }
                        .recoverCatching { e -> if (e is SnuttException && e.error == ErrorType.LECTURE_TIME_OVERLAP) null else throw e }
                        .getOrThrow()
                entryId?.let { setReminder(user, timetable, it, TimetableLectureReminderOption.TEN_MINUTES_BEFORE) }
            }
            Owner(user, timetable)
        }
    }

    private fun operate(
        run: StressRun,
        owner: Owner,
        lectures: List<Lecture>,
        stableLectureIds: Set<Long>,
        random: Random,
    ) {
        val userId = owner.user.id!!
        val timetableId = owner.timetable.id!!
        val entries =
            MySqlProbe.jdbc
                .queryForList(
                    "SELECT id, lecture_id FROM timetable_lecture WHERE timetable_id = ?",
                    timetableId,
                ).map { (it["id"] as Number).toLong() to (it["lecture_id"] as Number?)?.toLong() }
        val entryId = entries.randomOrNull(random)?.first ?: Long.MAX_VALUE
        val stableEntryId = entries.filter { it.second in stableLectureIds }.randomOrNull(random)?.first ?: Long.MAX_VALUE
        val operation = operations[random.nextInt(operations.size)]
        val allowed =
            when (operation) {
                ADD_OPERATION -> setOf(ErrorType.LECTURE_TIME_OVERLAP, ErrorType.DUPLICATE_LECTURE, ErrorType.LECTURE_NOT_FOUND)
                "modifyLectureTime" ->
                    setOf(ErrorType.TIMETABLE_LECTURE_NOT_FOUND, ErrorType.LECTURE_TIME_OVERLAP, ErrorType.LECTURE_NOT_FOUND)
                "setReminder" -> setOf(ErrorType.TIMETABLE_LECTURE_NOT_FOUND, ErrorType.TIMETABLE_LECTURE_REMINDER_INVALID_TIME)
                else -> setOf(ErrorType.TIMETABLE_LECTURE_NOT_FOUND, ErrorType.LECTURE_NOT_FOUND)
            }
        try {
            when (operation) {
                ADD_OPERATION ->
                    timetableLectureService.addLecture(
                        userId,
                        timetableId,
                        TimetableLectureAddRequest(lectures.random(random).id!!),
                    )
                "deleteLecture" -> timetableLectureService.deleteLecture(userId, timetableId, entryId)
                "modifyLectureTitle", "modifyStableLectureTitle" ->
                    timetableLectureService.modifyLecture(
                        userId,
                        timetableId,
                        if (operation == "modifyStableLectureTitle") stableEntryId else entryId,
                        TimetableLectureModifyRequest(courseTitle = "내 제목 ${random.nextInt(100)}"),
                    )
                "getTimetable" -> timetableService.getTimetableDisplay(userId, timetableId)
                "renameTimetable" -> timetableService.modifyTimetableTitle(userId, timetableId, "시간표 ${random.nextInt(1000)}")
                COPY_OPERATION -> timetableService.copyTimetable(userId, timetableId)
                "modifyLectureTime" -> {
                    val start = 540 + random.nextInt(17) * 30
                    val time = ClassPlaceAndTime(DayOfWeek.entries[random.nextInt(5)], "변경", start, start + 50)
                    timetableLectureService.modifyLecture(
                        userId,
                        timetableId,
                        entryId,
                        TimetableLectureModifyRequest(classPlaceAndTimes = listOf(time)),
                    )
                }
                "setReminder" ->
                    reminderService.modifyReminder(
                        userId,
                        timetableId,
                        entryId,
                        TimetableLectureReminderOption.entries.random(random),
                    )
                else -> error(operation)
            }
            run.record(operation, "ok", allowed = true)
        } catch (e: SnuttException) {
            run.record(operation, e.error.name, allowed = e.error in allowed)
        } catch (e: Exception) {
            run.record(operation, FailureKind.classify(e).name, allowed = false, detail = FailureKind.summarize(e))
        }
    }

    /** 평일 09:00~17:00 사이 30분 단위로 시작하는 75분 강의. 서로 겹치는 것이 많다. */
    private fun row(
        index: Int,
        random: Random,
    ): RowData {
        val start = 540 + random.nextInt(17) * 30
        return RowData(
            courseNumber = "S%03d".format(index),
            lectureNumber = "001",
            courseTitle = "부하강의$index",
            classTime = "${DAYS[random.nextInt(5)]}(${hhmm(start)}~${hhmm(start + 75)})",
            place = "301-${100 + index}",
            instructor = "교수$index",
        )
    }

    /** 수업 시간 변경 30%, 폐강 10%, 제목 변경 10%, 신설 [LECTURES]/10개. */
    private fun mutate(
        rows: List<RowData>,
        random: Random,
    ): List<RowData> {
        val changed =
            rows.mapNotNull { row ->
                when (random.nextInt(10)) {
                    0 -> null
                    1, 2, 3 ->
                        row(
                            row.courseNumber.drop(1).toInt(),
                            random,
                        ).copy(courseTitle = row.courseTitle, courseTitleEn = row.courseTitleEn)
                    4 -> row.copy(courseTitle = row.courseTitle + " (개편)")
                    else -> row
                }
            }
        return changed + (0 until LECTURES / 10).map { row(LECTURES + it, random) }
    }

    private fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    private fun Throwable.isInjected(): Boolean =
        generateSequence(this) { it.cause }.any { it.message.orEmpty().contains("injected fault") }

    companion object {
        /**
         * 동기화와 겹쳐도 통과해야 하는 요청: 동기화가 바꾸지 않는 강의의 제목 변경, 시간표 조회, 시간표 이름 변경(user 행 → timetable 순으로 잠근다.
         * 동기화가 시간표를 잠근 채 알림을 넣으며 user 행 FK S락을 기다리면 데드락이 난다).
         */
        val SAFE_OPERATIONS = listOf("modifyStableLectureTitle", "getTimetable", "renameTimetable")
        const val ADD_OPERATION = "addLecture"

        /** 대표 시간표를 복사한다. 복사는 원본을 잠그고 담긴 강의를 id 순으로 공유 락으로 잠근 뒤 행을 넣는다. */
        const val COPY_OPERATION = "copyTimetable"

        /** 동기화가 바꾸거나 지우는 강의일 수도 있는, 이미 담은 강의를 고치거나 지운다. */
        val EDIT_OPERATIONS = listOf("modifyLectureTitle", "deleteLecture")

        /** 리마인더 발송 시각을 다시 쓰는 요청. */
        val REMINDER_OPERATIONS = listOf("modifyLectureTime", "setReminder")

        const val LECTURES = 120
        const val THREADS_PER_USER = 5
        const val INITIAL_LECTURES_PER_USER = 6
        val DAYS = listOf("월", "화", "수", "목", "금")
        val LOAD_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}

/** 동기화가 바꾸지 않는 강의의 제목을 고치고 시간표를 읽는다(같은 시간표에 동기화가 바꾸는 강의도 있다). 통과해야 한다. */
@StressTest
class SugangSyncUnderLoadStressTest : AbstractSugangSyncUnderLoadStressTest("sugang-sync-under-load", SAFE_OPERATIONS)

/**
 * 동기화 중에 사용자가 동기화 대상 강의를 고치거나 지운다. 예전에는 (1) 동기화(timetable_lecture → timetable 순)와 강의 수정(timetable →
 * timetable_lecture 순)이 데드락이 났고, (2) 사용자가 리마인더 달린 강의를 지우면 동기화가 지워진 리마인더에 발송 시각을 넣다가 외래 키 위반으로
 * 학기 전체가 롤백됐다. 지금은 동기화가 시간표를 먼저 잠그고 잠근 뒤의 상태를 다시 읽는다(SugangSnuSyncService.sync).
 */
@StressTest
class SugangSyncWithLectureEditsStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-lecture-edits", SAFE_OPERATIONS + EDIT_OPERATIONS)

/**
 * 동기화 중에 사용자가 강의를 담는다. 예전에는 (1) 폐강 강의를 지울 때 사용자가 방금 담은 행 때문에 외래 키 위반으로 학기 전체가 롤백됐고,
 * (2) 동기화가 스냅숏으로 겹침을 판단해 시간이 겹치는 강의가 남았고, (3) touchUpdatedAt과 강의 추가가 데드락이 났다.
 */
@StressTest
class SugangSyncWithLectureAddsStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-lecture-adds", SAFE_OPERATIONS + ADD_OPERATION)

/**
 * 동기화 중에 사용자가 리마인더를 바꾸거나 수업 시간을 고친다. 예전에는 리마인더 발송 시각이 동기화 전 수업 시간으로 남았고, 발송 시각 교체끼리
 * gap 락 데드락, 시간표를 잠그지 않는 리마인더 설정의 unique·외래 키 위반이 났다.
 */
@StressTest
class SugangSyncWithReminderEditsStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-reminder-edits", SAFE_OPERATIONS + REMINDER_OPERATIONS)

/**
 * 동기화 중에 사용자가 시간표를 복사하고 강의를 담는다. 예전에는 (1) 복사가 담긴 순서대로 강의 FK 공유 락을 잡아 강의를 id 순으로 잠그는
 * 동기화와 데드락이 났고, (2) 동기화가 영향받는 시간표를 고른 뒤 복사된 시간표는 겹침이 그대로 남았다.
 */
@StressTest
class SugangSyncWithCopiesStressTest :
    AbstractSugangSyncUnderLoadStressTest("sugang-sync-with-copies", SAFE_OPERATIONS + ADD_OPERATION + COPY_OPERATION)

/**
 * 부하 중 동기화의 시간표 반영 묶음과 알림 배출이 실패한 뒤, 다음 실행이 남은 시간표 반영·알림을 정확히 한 번 마친다.
 * 예전에는 1단계가 커밋된 뒤 2단계가 실패하면 다음 실행이 그 시간표를 다시 보지 않아 겹침과 옛 리마인더 발송 시각이 남았다.
 */
@StressTest
class SugangSyncRecoversFromFaultsStressTest :
    AbstractSugangSyncUnderLoadStressTest(
        "sugang-sync-recovers-from-faults",
        SAFE_OPERATIONS + COPY_OPERATION,
        injectFaults = true,
    )
