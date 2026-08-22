import io

p = 'core/src/main/kotlin/com/wafflestudio/snutt/core/domain/timetable/service/TimetableLectureReminderService.kt'
s = io.open(p, encoding='utf-8').read()

old = '''import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional'''

new = '''import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.util.SemesterCalendar
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureReminder
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit'''

assert s.count(old) == 1
s = s.replace(old, new)

old = '''@Service
class TimetableLectureReminderService(
    private val timetableService: TimetableService,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
) {'''

new = '''data class DueReminderPush(
    val userId: Long,
    val body: String,
)

@Service
class TimetableLectureReminderService(
    private val timetableService: TimetableService,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureReminderRepository: TimetableLectureReminderRepository,
) {
    private var lastCleanupAt: Instant? = null

    /** 도래한 리마인더를 표시하고 발송 페이로드를 반환한다. 발송 자체는 호출부가 담당한다. */
    @Transactional
    fun processDueReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ): List<DueReminderPush> {
        cleanupPastSemesterReminders(now, current)
        val lastNotifiedBefore = now.toInstant().minus(TIME_WINDOW_MINUTES + 1, ChronoUnit.MINUTES)
        val pushes = mutableListOf<DueReminderPush>()
        dueWindows(now).forEach { window ->
            timetableLectureReminderRepository
                .findByNextFireInRange(window.day, window.startMinute, window.endMinute)
                .forEach { reminder ->
                    collect(reminder, now, current, listOf(window), lastNotifiedBefore)?.let { pushes += it }
                }
        }
        return pushes
    }

    private data class DueWindow(
        val day: Int,
        val startMinute: Int,
        val endMinute: Int,
    ) {
        fun contains(schedule: Schedule): Boolean =
            schedule.day.value == day && schedule.minute in startMinute..endMinute
    }

    private fun dueWindows(now: ZonedDateTime): List<DueWindow> {
        val end = Schedule.fromInstant(now.toInstant())
        val start = end.plusMinutes(-TIME_WINDOW_MINUTES.toInt())
        return if (start.day == end.day) {
            listOf(DueWindow(end.day.value, start.minute, end.minute))
        } else {
            listOf(DueWindow(start.day.value, start.minute, 1439), DueWindow(end.day.value, 0, end.minute))
        }
    }

    private fun cleanupPastSemesterReminders(
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
    ) {
        // 현재 학기보다 이전 학기의 리마인더는 더 이상 울리지 않으므로 정리한다(시간당 1회면 충분)
        val last = lastCleanupAt
        if (last != null && now.toInstant().isBefore(last.plus(Duration.ofHours(1)))) return
        val deleted =
            timetableLectureReminderRepository.deleteByPastSemesters(current.year, current.semester.value)
        if (deleted > 0) log.info("과거 학기 리마인더 정리: {}건", deleted)
        lastCleanupAt = now.toInstant()
    }

    private fun collect(
        reminder: TimetableLectureReminder,
        now: ZonedDateTime,
        current: SemesterCalendar.YearSemester,
        windows: List<DueWindow>,
        lastNotifiedBefore: Instant,
    ): DueReminderPush? {
        val timetableLecture =
            timetableLectureRepository.findByIdOrNull(reminder.timetableLectureId) ?: return null
        val timetable =
            timetableRepository.findByIdOrNull(timetableLecture.timetableId) ?: return null
        if (timetable.year != current.year || timetable.semester != current.semester) return null
        // 구 노티파이어와 동일하게 대표 시간표의 리마인더만 보낸다
        if (!timetable.isPrimary) return null

        // 이번 윈도우에 해당하고 아직 알리지 않은 스케줄만 대상으로 한다(같은 강의의 연속 스케줄 누락 방지)
        val dueSchedules =
            reminder.scheduleList.filter { schedule ->
                val lastNotified = schedule.recentNotifiedAt
                windows.any { it.contains(schedule) } &&
                    (lastNotified == null || lastNotified.isBefore(lastNotifiedBefore))
            }
        if (dueSchedules.isEmpty()) return null
        val courseTitle =
            timetableService
                .displaysOf(listOf(timetable))[timetable.id]
                ?.firstOrNull { it.id == timetableLecture.id }
                ?.courseTitle ?: return null
        val body =
            when {
                reminder.offsetMinutes == 0 -> courseTitle + " 강의 시간이에요."
                reminder.offsetMinutes > 0 -> courseTitle + " 강의 시작 " + reminder.offsetMinutes + "분 후예요."
                else -> courseTitle + " 강의 시작 " + (-reminder.offsetMinutes) + "분 전이에요."
            }

        reminder.scheduleList =
            reminder.scheduleList.map { schedule ->
                if (dueSchedules.any { it.day == schedule.day && it.minute == schedule.minute }) {
                    schedule.copy(recentNotifiedAt = now.toInstant())
                } else {
                    schedule
                }
            }
        reminder.recentNotifiedAt = now.toInstant()
        reminder.recomputeNextFire(now.toInstant().plusSeconds(60))
        timetableLectureReminderRepository.save(reminder)
        return DueReminderPush(userId = timetable.userId, body = body)
    }'''

assert s.count(old) == 1
s = s.replace(old, new)

old = ''') {
    fun getReminder('''
new = ''') {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getReminder('''
assert s.count(old) == 1
s = s.replace(old, new)

old = 'import org.springframework.stereotype.Service'
assert s.count(old) == 1
s = s.replace(old, 'import org.slf4j.LoggerFactory\n' + old, 1)

old = '''enum class TimetableLectureReminderOption(
    val offsetMinutes: Int?,
) {'''
new = '''enum class TimetableLectureReminderOption(
    val offsetMinutes: Int?,
) {
    ;'''
# noop guard - do not use

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('service patched')

# TIME_WINDOW_MINUTES 상수 추가
s = io.open(p, encoding='utf-8').read()
old = '''    companion object {
        fun fromOffsetMinutes(offsetMinutes: Int?): TimetableLectureReminderOption =
            when (offsetMinutes) {
                null -> NONE
                -10 -> TEN_MINUTES_BEFORE
                0 -> ZERO_MINUTE
                10 -> TEN_MINUTES_AFTER
                else -> throw IllegalArgumentException("Invalid offsetMinutes: $offsetMinutes")
            }
    }'''
new = '''    companion object {
        const val TIME_WINDOW_MINUTES = 10L

        fun fromOffsetMinutes(offsetMinutes: Int?): TimetableLectureReminderOption =
            when (offsetMinutes) {
                null -> NONE
                -10 -> TEN_MINUTES_BEFORE
                0 -> ZERO_MINUTE
                10 -> TEN_MINUTES_AFTER
                else -> throw IllegalArgumentException("Invalid offsetMinutes: $offsetMinutes")
            }
    }'''
assert s.count(old) == 1
s = s.replace(old, new)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('constant added')
