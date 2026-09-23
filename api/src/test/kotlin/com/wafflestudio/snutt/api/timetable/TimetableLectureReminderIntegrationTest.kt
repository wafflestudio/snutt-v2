package com.wafflestudio.snutt.api.timetable

import com.wafflestudio.snutt.api.AbstractMysqlIntegrationTest
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.model.LectureOverrides
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimetableLectureReminderIntegrationTest : AbstractMysqlIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun mysqlProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysqlJdbcUrl("reminder_test") }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }

    @Autowired
    lateinit var reminderService: TimetableLectureReminderService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var timetableRepository: TimetableRepository

    @Autowired
    lateinit var timetableLectureRepository: TimetableLectureRepository

    @Autowired
    lateinit var reminderRepository: TimetableLectureReminderRepository

    @Autowired
    lateinit var scheduleRepository: TimetableLectureReminderScheduleRepository

    private var userId: Long = 0
    private var timetableId: Long = 0
    private var timetableLectureId: Long = 0

    private val classTimes =
        listOf(
            ClassPlaceAndTime(day = DayOfWeek.MONDAY, place = "301동 101호", startMinute = 600, endMinute = 660),
            ClassPlaceAndTime(day = DayOfWeek.WEDNESDAY, place = "302동 201호", startMinute = 780, endMinute = 840),
        )

    @BeforeEach
    fun setUp() {
        scheduleRepository.deleteAll()
        reminderRepository.deleteAll()
        timetableLectureRepository.deleteAll()
        timetableRepository.deleteAll()
        userRepository.deleteAll()

        userId =
            userRepository
                .save(
                    User(
                        email = "reminder@snu.ac.kr",
                        isEmailVerified = true,
                        nickname = "reminderuser",
                        nicknameTag = "0000",
                        localId = "reminderuser",
                    ),
                ).id!!
        timetableId =
            timetableRepository
                .save(
                    Timetable(
                        userId = userId,
                        year = 2026,
                        semester = Semester.AUTUMN,
                        title = "나의 시간표",
                        themeId = 2L,
                        isPrimary = true,
                    ),
                ).id!!
        timetableLectureId = saveCustomLecture(classTimes).id!!
    }

    private fun saveCustomLecture(times: List<ClassPlaceAndTime>): TimetableLecture =
        timetableLectureRepository.save(
            TimetableLecture(
                timetableId = timetableId,
                overrides = LectureOverrides(courseTitle = "운영체제", classPlaceAndTimes = times),
            ),
        )

    @Test
    fun `같은 옵션으로 두 번 설정해도 스케줄이 유지된다`() {
        reminderService.modifyReminder(userId, timetableId, timetableLectureId, TimetableLectureReminderOption.TEN_MINUTES_BEFORE)
        reminderService.modifyReminder(userId, timetableId, timetableLectureId, TimetableLectureReminderOption.TEN_MINUTES_BEFORE)

        val reminder = reminderRepository.findByTimetableLectureId(timetableLectureId)!!
        val schedules = scheduleRepository.findByReminderId(reminder.id!!)
        assertEquals(2, schedules.size)
        assertEquals(
            setOf(Schedule(DayOfWeek.MONDAY, 590), Schedule(DayOfWeek.WEDNESDAY, 770)),
            schedules.map { it.toSchedule() }.toSet(),
        )
    }

    @Test
    fun `강의 시간을 바꿔도 공통 슬롯의 알림 이력은 유지된다`() {
        reminderService.modifyReminder(userId, timetableId, timetableLectureId, TimetableLectureReminderOption.ZERO_MINUTE)
        val reminder = reminderRepository.findByTimetableLectureId(timetableLectureId)!!
        val notifiedAt = Instant.parse("2026-09-21T00:30:00Z")
        val monday =
            scheduleRepository.findByReminderId(reminder.id!!).first { it.day == DayOfWeek.MONDAY }.apply {
                recentNotifiedAt = notifiedAt
            }
        scheduleRepository.save(monday)

        reminderService.recomputeForTimetableLecture(
            timetableLectureId,
            classTimes + ClassPlaceAndTime(day = DayOfWeek.FRIDAY, place = "303동 201호", startMinute = 540, endMinute = 600),
        )

        val schedules = scheduleRepository.findByReminderId(reminder.id!!)
        assertEquals(3, schedules.size)
        assertEquals(notifiedAt, schedules.first { it.day == DayOfWeek.MONDAY }.recentNotifiedAt)
    }

    @Test
    fun `중복된 수업 시간은 스케줄 하나로 합쳐진다`() {
        val duplicated = saveCustomLecture(listOf(classTimes.first(), classTimes.first()))

        reminderService.modifyReminder(userId, timetableId, duplicated.id!!, TimetableLectureReminderOption.ZERO_MINUTE)

        val reminder = reminderRepository.findByTimetableLectureId(duplicated.id!!)!!
        val schedules = scheduleRepository.findByReminderId(reminder.id!!)
        assertEquals(1, schedules.size)
        assertEquals(Schedule(DayOfWeek.MONDAY, 600), schedules.single().toSchedule())
    }
}
