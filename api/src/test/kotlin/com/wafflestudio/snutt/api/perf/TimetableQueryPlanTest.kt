package com.wafflestudio.snutt.api.perf

import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureClassTimeRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/** 시간표·시간표 강의·리마인더·관심강좌·빈자리 알림 조회의 실행계획. */
class TimetableQueryPlanTest : AbstractPerfTest() {
    @Autowired lateinit var timetableRepository: TimetableRepository

    @Autowired lateinit var timetableLectureRepository: TimetableLectureRepository

    @Autowired lateinit var lectureClassTimeRepository: LectureClassTimeRepository

    @Autowired lateinit var reminderScheduleRepository: TimetableLectureReminderScheduleRepository

    @Autowired lateinit var bookmarkLectureRepository: BookmarkLectureRepository

    @Autowired lateinit var vacancyNotificationRepository: VacancyNotificationRepository

    @Test
    fun `사용자 시간표 목록은 user_id로 시작하는 인덱스로 그 사용자 행만 읽는다`() {
        explainOne { timetableRepository.findByUserId(perf.typicalUserId) }
            .assertKey("timetable", "idx_timetable_user_semester", "uk_timetable_title")
            .assertAccess("timetable", "ref")
            .assertRowsAtMost("timetable", 100)
        // 시간표 773개인 사용자도 풀 스캔하지 않는다.
        explainOne { timetableRepository.findByUserId(perf.heavyTimetableUserId) }
            .assertKey("timetable", "idx_timetable_user_semester", "uk_timetable_title")
            .assertRowsAtMost("timetable", 2_000)
    }

    @Test
    fun `학기별 시간표는 user_id·year·semester 세 컬럼을 모두 인덱스로 찾는다`() {
        val current = perf.current
        explainOne { timetableRepository.findByUserIdAndYearAndSemester(perf.typicalUserId, current.year, current.semesterEnum) }
            .assertKey("timetable", "idx_timetable_user_semester", "uk_timetable_title")
            .assertAccess("timetable", "ref")
            .assertRowsAtMost("timetable", 10)
    }

    @Test
    fun `최근 수정 시간표는 사용자 행만 읽고 그 안에서만 정렬한다`() {
        // updated_at이 인덱스에 없어 정렬은 한다. 정렬 대상이 그 사용자의 시간표(극단 사용자 773개)로 묶여 있어야 한다.
        explainOne { timetableRepository.findFirstByUserIdOrderByUpdatedAtDesc(perf.heavyTimetableUserId) }
            .assertKey("timetable", "idx_timetable_user_semester", "uk_timetable_title")
            .assertRowsAtMost("timetable", 2_000)
    }

    @Test
    fun `대표 시간표 일괄 조회는 학기 인덱스로 그 학기 시간표만 읽는다`() {
        val current = perf.current
        explainOne { timetableRepository.findPrimaryTargetsByYearAndSemester(current.year, current.semesterEnum) }
            .assertNoFullScan()
            .assertKey("timetable", "idx_timetable_semester")
            .assertRowsAtMost("timetable", 20_000)
    }

    @Test
    fun `시간표 강의는 idx_timetable_lecture_timetable로 시간표 단위로 읽는다`() {
        explainOne { timetableLectureRepository.findByTimetableIdIn(listOf(perf.typicalTimetableId, perf.heavyTimetableId)) }
            .assertKey("timetable_lecture", "idx_timetable_lecture_timetable")
            .assertAccess("timetable_lecture", "range", "ref")
            .assertRowsAtMost("timetable_lecture", 100)
    }

    @Test
    fun `인기 강의를 담은 시간표 강의는 idx_timetable_lecture_lecture로 찾는다`() {
        explainOne { timetableLectureRepository.findByLectureIdIn(listOf(perf.popularLectureId)) }
            .assertKey("timetable_lecture", "idx_timetable_lecture_lecture")
            .assertNoFullScan()
    }

    @Test
    fun `리마인더 행 조회는 시간표 강의에서 강의와 리마인더를 PK·유니크 키로 붙인다`() {
        explainOne { timetableLectureRepository.findReminderRowsByTimetableId(perf.typicalTimetableId) }
            .assertNoFullScan()
            .assertKey("timetable_lecture", "idx_timetable_lecture_timetable")
            .assertAccess("lecture", "eq_ref")
            .assertKey("timetable_lecture_reminder", "uk_timetable_lecture_reminder")
            .assertNoFilesort()
    }

    @Test
    fun `발화할 리마인더는 idx_reminder_schedule_slot_notified로 요일·분 범위만 읽고 나머지는 PK로 붙인다`() {
        val current = perf.current
        explainOne {
            reminderScheduleRepository.findDueSchedules(
                DayOfWeek.TUESDAY,
                9 * 60 + 45,
                9 * 60 + 50,
                Instant.parse("2026-09-15T00:40:00Z"),
                current.year,
                current.semesterEnum,
            )
        }.assertNoFullScan()
            .assertKey("timetable_lecture_reminder_schedule", "idx_reminder_schedule_slot_notified")
            .assertAccess("timetable_lecture_reminder_schedule", "range")
            .assertAccess("timetable_lecture_reminder", "eq_ref")
            .assertAccess("timetable_lecture", "eq_ref")
            .assertAccess("timetable", "eq_ref")
            .assertNoFilesort()
    }

    @Test
    fun `강의 수업시간은 lecture_id 인덱스로 읽는다`() {
        explainOne {
            lectureClassTimeRepository.findAllByLectureIdInOrderById(
                listOf(
                    perf.popularLectureId,
                    perf.popularLectureId + 1,
                    perf.popularLectureId + 2,
                ),
            )
        }.assertKey("lecture_class_time", "idx_lecture_class_time_lecture_slot")
            .assertNoFullScan()
            .assertRowsAtMost("lecture_class_time", 20)
    }

    @Test
    fun `관심강좌는 사용자의 유니크 키로 찾고 강의를 PK로 붙인다`() {
        val current = perf.current
        explainOne { bookmarkLectureRepository.findLectures(perf.typicalUserId, current.year, current.semesterEnum) }
            .assertNoFullScan()
            .assertKey("bookmark_lecture", "uk_bookmark_lecture")
            .assertAccess("lecture", "eq_ref")
    }

    @Test
    fun `빈자리 알림 강의 목록은 사용자 유니크 키로 찾고 강의와 수강 현황을 PK로 붙인다`() {
        explainOne { vacancyNotificationRepository.findLectureDisplaysByUserId(perf.typicalUserId) }
            .assertNoFullScan()
            .assertKey("vacancy_notification", "uk_vacancy_notification")
            .assertAccess("lecture", "eq_ref")
            .assertAccess("lecture_registration_status", "eq_ref")
    }

    @Test
    fun `빈자리 배치의 강의별 구독자 조회는 idx_vacancy_notification_lecture를 쓴다`() {
        explainOne { vacancyNotificationRepository.findByLectureIdIn(listOf(perf.popularLectureId)) }
            .assertKey("vacancy_notification", "idx_vacancy_notification_lecture")
            .assertNoFullScan()
    }
}
