package com.wafflestudio.snutt.batch.sugang

import com.wafflestudio.snutt.batch.SugangXlsxFixture.RowData
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.test.IntegrationContainers
import com.wafflestudio.snutt.core.test.concurrency.LockContention
import com.wafflestudio.snutt.core.test.concurrency.pollUntil
import com.wafflestudio.snutt.core.test.stress.MySqlProbe
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 수강편람 동기화와 같은 시간표를 고치는 사용자 요청이 겹칠 때. holder(사용자 요청)가 커밋하지 않고 락을 쥔 채로 잡(contender)을 돌리고,
 * 잡이 락을 기다리거나 끝나면 holder를 커밋한다([LockContention]). 잡은 사용자 요청 하나 때문에 학기 전체가 롤백되면 안 되고,
 * 끝난 뒤 시간표에 시간 겹침이 없고 리마인더 발송 시각이 수업 시간과 맞아야 한다. 부하 버전은 `batch/.../stress/`.
 */
class SugangSyncConcurrencyTest : SugangSyncScenarioTest() {
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired lateinit var timetableService: TimetableService

    private val contention by lazy { LockContention(transactionManager, entityManagerFactory) }

    private lateinit var autumn: Coursebook

    private val algo =
        RowData(
            courseNumber = "400.100",
            lectureNumber = "001",
            courseTitle = "알고리즘",
            classTime = "월(10:00~11:15)",
            place = "301-101",
            instructor = "김교수",
        )
    private val compiler =
        RowData(
            courseNumber = "400.200",
            lectureNumber = "001",
            courseTitle = "컴파일러",
            classTime = "화(10:00~11:15)",
            place = "301-102",
            instructor = "박교수",
        )
    private val network =
        RowData(
            courseNumber = "400.300",
            lectureNumber = "001",
            courseTitle = "네트워크",
            classTime = "수(10:00~11:15)",
            place = "301-103",
            instructor = "최교수",
        )

    @BeforeEach
    fun seed() {
        autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        clock.setKst(LocalDateTime.of(2026, 8, 3, 10, 0))
    }

    private fun timetableOf(user: User): Timetable = fixtures.timetable(user, autumn)

    /** holder가 [userRequest]를 쥔 채로 [rows]로 동기화하고, 잡이 던진 예외를 돌려준다(성공하면 null). */
    private fun syncWhile(
        rows: List<RowData>,
        userRequest: () -> Unit,
    ): Throwable? {
        stubSite(rows)
        val outcome =
            contention.holdThenContend(
                contenders = 1,
                holder = { runCatching(userRequest).exceptionOrNull() },
                contender = { runCatching { sugangSnuSyncJob.run(null) }.exceptionOrNull() },
            )
        outcome.results[0].getOrThrow()?.let { throw AssertionError("사용자 요청이 실패했다", it) }
        return outcome.results[1].getOrThrow()
    }

    @Test
    fun `동기화가 영향받는 시간표를 읽은 뒤 사용자가 폐강될 강의를 담아도 잡은 나머지를 반영하고 그 강의는 다음 동기화에서 닫는다`() {
        sync(listOf(algo, compiler, network))
        val alice = userWithDevice("alice")
        val aliceTimetable = timetableOf(alice)
        addLecture(alice, aliceTimetable, lecture(network.courseNumber))
        val bob = userWithDevice("bob")
        val bobTimetable = timetableOf(bob)
        val renamed = algo.copy(courseTitle = "알고리즘 (개편)")

        // compiler와 network가 폐강되고 algo 제목이 바뀐다. bob이 그 사이 compiler를 담는다.
        val error = syncWhile(listOf(renamed)) { addLecture(bob, bobTimetable, lecture(compiler.courseNumber)) }

        assertNull(error)
        assertEquals("알고리즘 (개편)", lecture(algo.courseNumber).courseTitle)
        assertNull(lectureOrNull(network.courseNumber))
        assertEquals(emptyList<Long?>(), timetableLectureIds(aliceTimetable))
        // bob이 담은 compiler는 아직 남는다. 엑셀에 없으므로 다음 동기화가 닫는다.
        assertEquals(listOf(lecture(compiler.courseNumber).id), timetableLectureIds(bobTimetable))

        sync(listOf(renamed))

        assertNull(lectureOrNull(compiler.courseNumber))
        assertEquals(emptyList<Long?>(), timetableLectureIds(bobTimetable))
        assertEquals(1, notificationsOf(bob).size)
    }

    @Test
    fun `동기화 중에 사용자가 리마인더 달린 강의를 지워도 잡은 실패하지 않는다`() {
        sync(listOf(algo, compiler))
        val alice = userWithDevice("alice")
        val timetable = timetableOf(alice)
        val entryId = addLecture(alice, timetable, lecture(algo.courseNumber))
        setReminder(alice, timetable, entryId, TimetableLectureReminderOption.TEN_MINUTES_BEFORE)

        val error =
            syncWhile(listOf(algo.copy(classTime = "목(13:00~14:15)"), compiler)) {
                timetableLectureService.deleteLecture(alice.id!!, timetable.id!!, entryId)
            }

        assertNull(error)
        assertEquals(listOf(DayOfWeek.THURSDAY), classTimes(lecture(algo.courseNumber)).map { it.day })
        assertEquals(emptyList<Long?>(), timetableLectureIds(timetable))
        assertEquals(0, reminderRepository.count())
    }

    @Test
    fun `동기화가 수업 시간을 옮기는 동안 사용자가 그 시간에 다른 강의를 담아도 겹침이 남지 않는다`() {
        sync(listOf(algo, compiler))
        val alice = userWithDevice("alice")
        val timetable = timetableOf(alice)
        addLecture(alice, timetable, lecture(algo.courseNumber))

        // algo가 compiler와 같은 화요일 10시로 옮겨진다. alice는 동기화 전 수업 시간으로 겹침을 검사하고 compiler를 담는다.
        val error =
            syncWhile(listOf(algo.copy(classTime = "화(10:00~11:15)"), compiler)) {
                addLecture(alice, timetable, lecture(compiler.courseNumber))
            }

        assertNull(error)
        // 동기화는 옮긴 algo가 겹치면 algo를 시간표에서 뺀다(TIMETABLE_LECTURE_OVERWRITTEN).
        assertEquals(listOf(lecture(compiler.courseNumber).id), timetableLectureIds(timetable))
    }

    @Test
    fun `동기화가 수업 시간을 옮기는 동안 사용자가 리마인더를 걸어도 발송 시각은 새 수업 시간을 따른다`() {
        sync(listOf(algo))
        val alice = userWithDevice("alice")
        val timetable = timetableOf(alice)
        val entryId = addLecture(alice, timetable, lecture(algo.courseNumber))

        val error =
            syncWhile(listOf(algo.copy(classTime = "목(13:00~14:15)"))) {
                setReminder(alice, timetable, entryId, TimetableLectureReminderOption.TEN_MINUTES_BEFORE)
            }

        assertNull(error)
        assertEquals(listOf(Schedule(DayOfWeek.THURSDAY, 13 * 60 - 10)), reminderSchedules(entryId))
    }

    @Test
    fun `시간표 복사는 강의를 id 순으로 잠그므로 강의를 id 순으로 잠그는 동기화와 데드락이 나지 않는다`() {
        sync(listOf(algo, compiler))
        val alice = userWithDevice("alice")
        val timetable = timetableOf(alice)
        // 시간표에는 id가 큰 강의(compiler)를 먼저 담는다. 복사가 담긴 순서대로 강의 FK 공유 락을 잡으면 compiler → algo 순이 된다.
        val (low, high) = listOf(lecture(algo.courseNumber), lecture(compiler.courseNumber)).sortedBy { it.id }
        addLecture(alice, timetable, high)
        addLecture(alice, timetable, low)

        // 동기화 1단계처럼 강의를 id 순으로 잠그는 트랜잭션이 작은 id를 잡은 상태에서 복사가 시작된다.
        val mysql = IntegrationContainers.mysql
        DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { sync1 ->
            sync1.autoCommit = false
            sync1.createStatement().use { it.executeQuery("SELECT id FROM lecture WHERE id = ${low.id} FOR UPDATE").close() }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val copy = executor.submit<Timetable> { timetableService.copyTimetable(alice.id!!, timetable.id!!) }
                pollUntil(message = { "복사가 강의 락을 기다리지 않는다" }) {
                    MySqlProbe.jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits", Int::class.java)!! > 0
                }
                // 복사가 큰 id 강의에 공유 락을 쥔 채 작은 id를 기다리면 여기서 데드락(1213)이 난다.
                sync1.createStatement().use { it.executeQuery("SELECT id FROM lecture WHERE id = ${high.id} FOR UPDATE").close() }
                sync1.commit()

                val copied = copy.get(30, TimeUnit.SECONDS)
                assertEquals(listOf(high.id, low.id), timetableLectureIds(copied))
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
