package com.wafflestudio.snutt.batch.sugang

import com.wafflestudio.snutt.batch.SugangXlsxFixture.RowData
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.push.PushSendResult
import com.wafflestudio.snutt.core.domain.coursebook.model.Coursebook
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.test.IntegrationContainers
import com.wafflestudio.snutt.core.test.concurrency.LockContention
import com.wafflestudio.snutt.core.test.concurrency.pollUntil
import com.wafflestudio.snutt.core.test.fault.DbFault
import com.wafflestudio.snutt.core.test.fault.DbFaults
import com.wafflestudio.snutt.core.test.stress.MySqlProbe
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * 동기화가 강의 반영(1단계)을 커밋한 뒤 시간표 반영·알림을 끝내지 못했을 때 다음 실행이 이어서 마치는지 본다.
 * 장애는 [DbFaults](DB 트리거)로 그 단계의 쓰기를 실패시켜 만든다. 커밋 전에 끝나는 장애(프로세스 종료, 연결 끊김)와 남는 DB 상태가 같다.
 *
 * 계약: 1단계가 커밋되면 강의는 새 값으로 남는다. 시간표 반영·알림은 1단계 트랜잭션이 남긴 기록(`sugang_sync_pending_*`)과 알림
 * outbox(`sugang_sync_outbox`)로 다음 실행이 정확히 한 번 마친다. 1단계가 실패하면 모두 되돌린다(`SugangSyncDiffTest`의 트랜잭션 경계).
 */
class SugangSyncRecoveryTest : SugangSyncScenarioTest() {
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired lateinit var timetableService: TimetableService

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

    /** algo가 compiler와 같은 화요일 10시로 옮겨진다. 둘 다 담은 시간표에서는 algo가 밀려난다. */
    private val moved = listOf(algo.copy(classTime = "화(10:00~11:15)"), compiler)

    @BeforeEach
    fun seed() {
        autumn = fixtures.coursebook(2026, Semester.AUTUMN)
        clock.setKst(LocalDateTime.of(2026, 8, 3, 10, 0))
    }

    private inner class Member(
        val user: User,
        val timetable: Timetable,
    ) {
        fun lectureIds() = timetableLectureIds(timetable)

        fun messages() = notificationsOf(user).map { it.message }

        fun updatedAt(): Instant = timetableRepository.findById(timetable.id!!).get().updatedAt!!

        fun entryId(row: RowData): Long =
            timetableLectureRepository.findByTimetableIdIn(listOf(timetable.id!!)).single { it.lectureId == lecture(row).id }.id!!
    }

    private fun member(
        localId: String,
        vararg rows: RowData,
    ): Member {
        val user = userWithDevice(localId)
        val timetable = fixtures.timetable(user, autumn)
        rows.forEach { addLecture(user, timetable, lecture(it)) }
        return Member(user, timetable)
    }

    private fun lecture(row: RowData) = lecture(row.courseNumber, row.lectureNumber)

    private fun pushBodies(): Map<String, String> = sentPushes().associate { it.fcmRegistrationId to it.message.body }

    private fun leftovers(): Map<String, Int> =
        listOf("sugang_sync_pending_lecture", "sugang_sync_pending_timetable", "sugang_sync_outbox", "sugang_sync_push_outbox")
            .associateWith { MySqlProbe.jdbc.queryForObject("SELECT COUNT(*) FROM $it", Int::class.java)!! }
            .filterValues { it > 0 }

    private fun updated(title: String) = "2026-2학기 '나의 시간표' 시간표의 '$title' 강의가 업데이트 되었습니다.(항목: 강의 시간/장소)"

    private fun overwritten(title: String) = "2026-2학기 '나의 시간표' 시간표의 '$title' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다."

    /** alice는 algo·compiler, bob은 algo를 담고 algo에 10분 전 리마인더를 건다. */
    private fun aliceAndBob(): Pair<Member, Member> {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo, compiler)
        val bob = member("bob", algo)
        setReminder(bob.user, bob.timetable, bob.entryId(algo), TimetableLectureReminderOption.TEN_MINUTES_BEFORE)
        clearPushes()
        return alice to bob
    }

    private fun assertRecovered(
        alice: Member,
        bob: Member,
        recoveredAt: Instant,
    ) {
        assertEquals(listOf(lecture(compiler).id), alice.lectureIds())
        assertEquals(listOf(Schedule(DayOfWeek.TUESDAY, 590)), reminderSchedules(bob.entryId(algo)))
        assertEquals(listOf(overwritten("알고리즘")), alice.messages())
        assertEquals(listOf(updated("알고리즘")), bob.messages())
        assertEquals(recoveredAt, alice.updatedAt())
        assertEquals(recoveredAt, bob.updatedAt())
        assertEquals(emptyMap<String, Int>(), leftovers())
    }

    /** 한 번 더 돌려도 같은 변경을 다시 반영하거나 알리지 않는다. */
    private fun assertNoReplay(
        alice: Member,
        bob: Member,
    ) {
        clearPushes()
        clock.advance(Duration.ofHours(1))
        sync(moved)
        assertEquals(1, alice.messages().size)
        assertEquals(1, bob.messages().size)
        assertEquals(emptyMap<String, String>(), pushBodies())
    }

    @Test
    fun `시간표 반영이 실패해도 강의는 커밋된 채 남고 다음 실행이 리마인더·겹침·알림·updated_at을 바로잡는다`() {
        val (alice, bob) = aliceAndBob()
        clock.setKst(LocalDateTime.of(2026, 8, 4, 10, 0))

        DbFaults.failing("timetable", "UPDATE").use {
            assertThrows<IllegalStateException> { sync(moved) }
        }

        // 1단계(강의 반영)는 커밋됐다. 시간표는 아직 옛 상태다.
        assertEquals(listOf(DayOfWeek.TUESDAY), classTimes(lecture(algo)).map { it.day })
        // 강의는 바뀌었으므로 수강편람 updated_at도 고친다(클라이언트가 강의 목록을 다시 받는다).
        assertEquals(clock.instant(), coursebookRepository.findAll().single().updatedAt)
        assertEquals(listOf(lecture(algo).id, lecture(compiler).id), alice.lectureIds())
        assertEquals(listOf(Schedule(DayOfWeek.MONDAY, 590)), reminderSchedules(bob.entryId(algo)))
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())

        // 다음 실행은 엑셀과 DB가 같아 바뀐 강의가 없지만, 남은 시간표 반영부터 마친다.
        clock.setKst(LocalDateTime.of(2026, 8, 4, 11, 0))
        sync(moved)

        assertRecovered(alice, bob, clock.instant())
        assertEquals(
            mapOf(
                "fcm-alice" to "강의 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
                "fcm-bob" to "강의 1개가 변경되었습니다. 알림함에서 자세히 확인하세요.",
            ),
            pushBodies(),
        )
        assertNoReplay(alice, bob)
    }

    @Test
    fun `시간표 반영이 락 충돌로 재시도를 다 써도 다음 실행이 이어서 반영한다`() {
        val (alice, bob) = aliceAndBob()

        DbFaults.failing("timetable", "UPDATE", fault = DbFault.DEADLOCK).use {
            assertThrows<IllegalStateException> { sync(moved) }
        }
        assertEquals(listOf(lecture(algo).id, lecture(compiler).id), alice.lectureIds())

        clock.advance(Duration.ofMinutes(30))
        sync(moved)

        assertRecovered(alice, bob, clock.instant())
        assertNoReplay(alice, bob)
    }

    @Test
    fun `알림을 넣지 못해도 시간표는 다시 반영하지 않고 다음 실행이 알림과 푸시를 한 번만 남긴다`() {
        val (alice, bob) = aliceAndBob()
        val bookmarker = userWithDevice("carol")
        bookmarkService.addLecture(bookmarker.id!!, lecture(algo).id!!)
        val changedAt = clock.instant()

        DbFaults.failing("notification", "INSERT").use {
            assertThrows<RuntimeException> { sync(moved) }
        }

        // 시간표 반영은 커밋됐고 알림만 outbox에 남았다. 푸시는 알림을 넣은 뒤에 보낸다.
        assertEquals(listOf(lecture(compiler).id), alice.lectureIds())
        assertEquals(listOf(Schedule(DayOfWeek.TUESDAY, 590)), reminderSchedules(bob.entryId(algo)))
        assertEquals(0, notificationRepository.count())
        assertEquals(emptyMap<String, String>(), pushBodies())

        clock.advance(Duration.ofMinutes(30))
        sync(moved)

        assertEquals(listOf(lecture(compiler).id), alice.lectureIds())
        assertEquals(listOf(overwritten("알고리즘")), alice.messages())
        assertEquals(listOf(updated("알고리즘")), bob.messages())
        assertEquals(
            listOf("2026-2학기 관심강좌 목록의 '알고리즘' 강의가 업데이트 되었습니다.(항목: 강의 시간/장소)"),
            notificationsOf(bookmarker).map { it.message },
        )
        // 시간표는 첫 실행이 반영했으므로 updated_at도 그때 값이다.
        assertEquals(changedAt, alice.updatedAt())
        assertEquals(
            mapOf(
                "fcm-alice" to "강의 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
                "fcm-bob" to "강의 1개가 변경되었습니다. 알림함에서 자세히 확인하세요.",
            ),
            pushBodies(),
        )
        assertEquals(emptyMap<String, Int>(), leftovers())
        assertNoReplay(alice, bob)
    }

    @Test
    fun `폐강 강의를 지우지 못해도 이미 커밋한 시간표 변경의 알림과 푸시는 보내고 잡을 실패로 끝낸다`() {
        sync(listOf(algo, compiler))
        val alice = member("alice", algo)
        val bob = member("bob", compiler)
        clearPushes()
        val renamed = algo.copy(courseTitle = "알고리즘 (개편)")

        DbFaults.failing("lecture", "DELETE").use {
            assertThrows<RuntimeException> { sync(listOf(renamed)) }
        }

        assertEquals(
            listOf("2026-2학기 '나의 시간표' 시간표의 '알고리즘 (개편)' 강의가 업데이트 되었습니다.(항목: 강의명)"),
            alice.messages(),
        )
        // 폐강 강의는 시간표에서 빠졌지만(2단계) 강의 행은 남았다. 다음 동기화가 다시 닫는다.
        assertEquals(emptyList<Long?>(), bob.lectureIds())
        assertEquals(listOf(NotificationType.LECTURE_REMOVE), notificationsOf(bob.user).map { it.type })
        assertEquals(
            mapOf(
                "fcm-alice" to "강의 1개가 변경되었습니다. 알림함에서 자세히 확인하세요.",
                "fcm-bob" to "강의 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
            ),
            pushBodies(),
        )

        clearPushes()
        sync(listOf(renamed))

        assertNull(lectureOrNull(compiler.courseNumber))
        assertEquals(1, alice.messages().size)
        assertEquals(1, notificationsOf(bob.user).size)
        assertEquals(emptyMap<String, String>(), pushBodies())
    }

    @Test
    fun `시간표 반영이 영향받는 시간표를 고른 뒤 복사된 시간표도 바로잡는다`() {
        sync(listOf(algo, compiler))
        // 시간표 반영은 시간표 100개씩 묶는다. 복사할 원본(source)을 두 번째 묶음에 둔다.
        val first = member("first", algo)
        repeat(99) { fixtures.addLecture(fixtures.timetable(fixtures.user(), autumn), lecture(algo)) }
        val source = member("source", algo, compiler)
        lateinit var copy: Timetable

        // holder가 첫 시간표를 잡고 있는 동안 동기화는 영향받는 시간표를 이미 골랐고 첫 묶음 락을 기다린다. 그사이 source를 복사한다.
        stubSite(moved)
        val outcome =
            LockContention(transactionManager, entityManagerFactory).holdThenContend(
                contenders = 1,
                whileContended = { copy = timetableService.copyTimetable(source.user.id!!, source.timetable.id!!) },
                holder = {
                    runCatching {
                        timetableService.modifyTimetableTitle(
                            first.user.id!!,
                            first.timetable.id!!,
                            "잠금",
                        )
                    }.exceptionOrNull()
                },
                contender = { runCatching { sugangSnuSyncJob.run(null) }.exceptionOrNull() },
            )
        outcome.results.forEach { assertNull(it.getOrThrow()) }

        assertEquals(listOf(lecture(compiler).id), source.lectureIds())
        // 복사본도 원본처럼 겹친 algo가 빠진다. 복사본에는 처음 고른 시간표가 아니므로 변경 알림 없이 밀려난 알림만 간다.
        assertEquals(listOf(lecture(compiler).id), timetableLectureIds(copy))
        assertEquals(
            listOf(
                overwritten("알고리즘"),
                "2026-2학기 '${copy.title}' 시간표의 '알고리즘' 강의가 업데이트되었으나, 시간표의 다른 강의와 겹쳐 삭제되었습니다.",
            ),
            source.messages(),
        )
    }

    @Test
    fun `1단계 직후 죽어 시간표 펼치기가 늦어져도 그사이 강의를 담은 사용자에게는 변경 알림을 보내지 않는다`() {
        val (alice, bob) = aliceAndBob()

        // 강의 변경이 닿는 시간표를 기록하지 못한다(1단계 커밋 뒤 프로세스가 죽은 것과 같은 상태).
        DbFaults.failing("sugang_sync_pending_timetable", "INSERT").use {
            assertThrows<IllegalStateException> { sync(moved) }
        }
        // 그사이 carol은 바뀐 수업 시간(화 10시)을 보고 algo를 담는다.
        val carol = member("carol", algo)

        sync(moved)

        assertRecovered(alice, bob, clock.instant())
        assertEquals(emptyList<String>(), carol.messages())
        assertEquals(listOf(lecture(algo).id), carol.lectureIds())
    }

    @Test
    fun `알림을 넣은 뒤 푸시를 보내지 못하면 다음 실행이 그 푸시를 보낸다`() {
        val (alice, bob) = aliceAndBob()
        whenever(pushClient.sendMessages(any())).thenThrow(IllegalStateException("FCM 연결 끊김"))

        assertThrows<IllegalStateException> { sync(moved) }
        assertEquals(1, alice.messages().size)
        assertEquals(1, bob.messages().size)

        whenever(pushClient.sendMessages(any())).thenReturn(PushSendResult())
        clearPushes()
        sync(moved)

        assertEquals(
            mapOf(
                "fcm-alice" to "강의 1개가 삭제되었습니다. 알림함에서 자세히 확인하세요.",
                "fcm-bob" to "강의 1개가 변경되었습니다. 알림함에서 자세히 확인하세요.",
            ),
            pushBodies(),
        )
        assertEquals(emptyMap<String, Int>(), leftovers())
        assertNoReplay(alice, bob)
    }

    @Test
    fun `동기화 락을 풀지 못해도 끝난 동기화를 실패로 덮지 않는다`() {
        val (alice, bob) = aliceAndBob()
        // 락을 쥔 커넥션이 동기화 도중 끊긴다(네트워크 단절, wait_timeout). MySQL은 끊긴 커넥션의 락을 풀고, RELEASE_LOCK은 실패한다.
        // 푸시는 락 안의 마지막 단계라 그 시점에 끊는다.
        var killed = false
        whenever(pushClient.sendMessages(any())).thenAnswer {
            if (!killed) {
                val holder = MySqlProbe.jdbc.queryForObject("SELECT IS_USED_LOCK('snutt.sugang_sync')", Long::class.java)
                MySqlProbe.jdbc.execute("KILL $holder")
                killed = true
            }
            PushSendResult()
        }

        sync(moved)

        assertTrue(killed)
        assertRecovered(alice, bob, clock.instant())
        assertEquals(setOf("fcm-alice", "fcm-bob"), pushBodies().keys)
        // KILL은 세션 정리를 기다리지 않고 돌아오므로 락이 풀릴 때까지 기다린다(다음 테스트의 GET_LOCK이 실패하지 않게).
        pollUntil { MySqlProbe.jdbc.queryForObject("SELECT IS_USED_LOCK('snutt.sugang_sync')", Long::class.java) == null }
    }

    @Test
    fun `다른 동기화가 돌고 있으면 아무것도 바꾸지 않고 끝낸다`() {
        val (alice, _) = aliceAndBob()
        val mysql = IntegrationContainers.mysql
        DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { other ->
            other.createStatement().use { it.executeQuery("SELECT GET_LOCK('snutt.sugang_sync', 0)").close() }

            val e = assertThrows<IllegalStateException> { sync(moved) }

            assertEquals("다른 수강편람 동기화가 돌고 있다(snutt.sugang_sync)", e.message)
        }
        assertEquals(listOf(DayOfWeek.MONDAY), classTimes(lecture(algo)).map { it.day })
        assertEquals(listOf(lecture(algo).id, lecture(compiler).id), alice.lectureIds())
    }
}
