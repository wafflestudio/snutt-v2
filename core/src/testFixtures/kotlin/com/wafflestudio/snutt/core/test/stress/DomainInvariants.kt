package com.wafflestudio.snutt.core.test.stress

import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.timetable.model.Schedule
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureReminderScheduleRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.service.ClassTimeUtils
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext

/**
 * 부하가 끝난 뒤 DB 전체에서 성립해야 하는 불변식. 위반마다 한 줄 설명을 돌려준다(없으면 빈 목록).
 * 조회는 [MySqlProbe]의 별도 연결로 하고, 수업 시간 해석(override JSON)만 애플리케이션 리포지토리를 쓴다(부하가 끝난 뒤라 풀을 다투지 않는다).
 */
class DomainInvariants(
    beans: ApplicationContext,
) {
    private val timetableLectureRepository: TimetableLectureRepository = beans.getBean()
    private val lectureService: LectureService = beans.getBean()
    private val reminderRepository: TimetableLectureReminderRepository = beans.getBean()
    private val reminderScheduleRepository: TimetableLectureReminderScheduleRepository = beans.getBean()
    private val jdbc = MySqlProbe.jdbc

    fun violations(): List<String> =
        timetableViolations() + themeViolations() + evaluationViolations() + reminderViolations() + orphanViolations()

    fun assertHolds() {
        val violations = violations()
        if (violations.isNotEmpty()) throw AssertionError("불변식 위반 ${violations.size}건:\n" + violations.take(20).joinToString("\n"))
    }

    private fun rows(
        description: String,
        sql: String,
    ): List<String> = jdbc.queryForList(sql).map { "$description: $it" }

    /** 시간 겹침 없음, 같은 강의 중복 없음, 학기마다 대표 시간표 1개 이하. */
    fun timetableViolations(): List<String> {
        val entries = timetableLectureRepository.findAll()
        val lectureTimes = lectureService.classTimesByLectureId(entries.mapNotNull { it.lectureId }.distinct())
        val overlaps =
            entries.groupBy { it.timetableId }.flatMap { (timetableId, lectures) ->
                val times = lectures.map { it to (it.overrides?.classPlaceAndTimes ?: lectureTimes[it.lectureId].orEmpty()) }
                times.indices.flatMap { i ->
                    (i + 1 until times.size).mapNotNull { j ->
                        if (ClassTimeUtils.timesOverlap(times[i].second, times[j].second)) {
                            "시간표 $timetableId 의 강의 ${times[i].first.id}(${times[i].second})와 ${times[j].first.id}(${times[j].second})가 겹친다"
                        } else {
                            null
                        }
                    }
                }
            }
        return overlaps +
            rows(
                "같은 강의가 한 시간표에 두 번",
                "SELECT timetable_id, lecture_id, COUNT(*) c FROM timetable_lecture WHERE lecture_id IS NOT NULL " +
                    "GROUP BY timetable_id, lecture_id HAVING c > 1",
            ) +
            rows(
                "대표 시간표가 둘 이상인 학기",
                "SELECT user_id, year, semester, COUNT(*) c FROM timetable WHERE is_primary GROUP BY user_id, year, semester HAVING c > 1",
            )
    }

    /** 리마인더마다 발송 시각 = 현재 수업 시간(override가 있으면 그것) + offset이고, 수업 시간이 없는 리마인더는 없다. */
    fun reminderViolations(): List<String> {
        val reminders = reminderRepository.findAll()
        val entries = timetableLectureRepository.findAllById(reminders.map { it.timetableLectureId }).associateBy { it.id!! }
        val lectureTimes = lectureService.classTimesByLectureId(entries.values.mapNotNull { it.lectureId })
        val schedules = reminderScheduleRepository.findAll().groupBy { it.reminderId }
        return reminders.mapNotNull { reminder ->
            val entry = entries[reminder.timetableLectureId] ?: return@mapNotNull "강의가 없는 리마인더 ${reminder.id}"
            val times = entry.overrides?.classPlaceAndTimes ?: lectureTimes[entry.lectureId].orEmpty()
            val expected = times.map { Schedule(it.day, it.startMinute).plusMinutes(reminder.offsetMinutes) }.toSet()
            val actual = schedules[reminder.id].orEmpty().map { it.toSchedule() }.toSet()
            if (expected.isEmpty() || expected != actual) "리마인더 ${reminder.id}: 기대 $expected, 실제 $actual" else null
        }
    }

    /** [userIds] 각각에게 시간표가 하나 이상 남아 있다(마지막 시간표는 지울 수 없다). */
    fun usersWithoutTimetable(userIds: Collection<Long>): List<String> =
        userIds
            .filter { userId ->
                jdbc.queryForObject("SELECT COUNT(*) FROM timetable WHERE user_id = ?", Int::class.java, userId) == 0
            }.map { "시간표가 하나도 없는 사용자: $it" }

    /** 팔레트 범위, 시간표·기본 테마가 빌트인이거나 자기 테마인지. */
    fun themeViolations(): List<String> =
        rows(
            "팔레트 범위를 벗어난 palette_index",
            "SELECT tl.id, tl.palette_index, JSON_LENGTH(th.colors) AS palette FROM timetable_lecture tl " +
                "JOIN timetable t ON t.id = tl.timetable_id JOIN timetable_theme th ON th.id = t.theme_id " +
                "WHERE tl.custom_color IS NULL AND tl.palette_index >= JSON_LENGTH(th.colors)",
        ) +
            rows(
                "남의 테마를 쓰는 시간표",
                "SELECT t.id, t.user_id, th.user_id AS theme_owner FROM timetable t JOIN timetable_theme th ON th.id = t.theme_id " +
                    "WHERE th.user_id IS NOT NULL AND th.user_id <> t.user_id",
            ) +
            rows(
                "남의 테마가 기본 테마",
                "SELECT p.user_id, p.default_theme_id FROM user_preference p JOIN timetable_theme th ON th.id = p.default_theme_id " +
                    "WHERE th.user_id IS NOT NULL AND th.user_id <> p.user_id",
            )

    /** course 집계 = 보이는 강의평, like_count = 공감 행 수. */
    fun evaluationViolations(): List<String> =
        rows(
            "course 집계가 강의평과 다르다",
            "SELECT c.id, c.eval_count, c.avg_rating, a.cnt, a.avg_rating AS actual_avg FROM course c " +
                "LEFT JOIN (SELECT course_id, COUNT(*) cnt, AVG(rating) avg_rating FROM evaluation " +
                "WHERE NOT is_hidden GROUP BY course_id) a " +
                "ON a.course_id = c.id " +
                "WHERE c.eval_count <> COALESCE(a.cnt, 0) " +
                "OR (a.cnt IS NOT NULL AND (c.avg_rating IS NULL OR ABS(c.avg_rating - a.avg_rating) > 1e-9)) " +
                "OR (a.cnt IS NULL AND c.avg_rating IS NOT NULL)",
        ) +
            rows(
                "like_count가 공감 수와 다르다",
                "SELECT e.id, e.like_count, (SELECT COUNT(*) FROM evaluation_like l WHERE l.evaluation_id = e.id) AS likes " +
                    "FROM evaluation e HAVING e.like_count <> likes",
            )

    /** 부모가 없는 행. 외래 키가 막아야 하지만 FOREIGN_KEY_CHECKS·벌크 삭제 경로가 있으므로 따로 본다. */
    fun orphanViolations(): List<String> =
        ORPHAN_CHECKS.flatMap { (child, column, parent) ->
            rows(
                "고아 $child.$column",
                "SELECT c.id FROM $child c LEFT JOIN $parent p ON p.id = c.$column WHERE c.$column IS NOT NULL AND p.id IS NULL",
            )
        }

    private companion object {
        val ORPHAN_CHECKS =
            listOf(
                Triple("timetable", "user_id", "`user`"),
                Triple("timetable", "theme_id", "timetable_theme"),
                Triple("timetable_lecture", "timetable_id", "timetable"),
                Triple("timetable_lecture", "lecture_id", "lecture"),
                Triple("timetable_lecture_reminder", "timetable_lecture_id", "timetable_lecture"),
                Triple("timetable_lecture_reminder_schedule", "reminder_id", "timetable_lecture_reminder"),
                Triple("evaluation_like", "evaluation_id", "evaluation"),
                Triple("evaluation", "course_id", "course"),
                Triple("lecture_class_time", "lecture_id", "lecture"),
            )
    }
}
