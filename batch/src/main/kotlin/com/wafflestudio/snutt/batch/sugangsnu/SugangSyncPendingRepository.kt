package com.wafflestudio.snutt.batch.sugangsnu

import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 1단계가 남긴, 시간표에 반영할 강의 변경(`sugang_sync_pending_lecture`). */
data class PendingLecture(
    val id: Long,
    val lectureId: Long,
    val classTimesChanged: Boolean,
    val changedLabels: LocalizedMessage,
    val expanded: Boolean = false,
)

/** 강의 변경 하나가 닿는 시간표 하나(`sugang_sync_pending_timetable`). */
data class PendingTimetable(
    val pendingLectureId: Long,
    val timetableId: Long,
    val notify: Boolean,
)

/** 커밋 뒤 푸시로 모아 보낼 시간표 변경 종류. */
enum class PushKind { UPDATED, DELETED }

data class OutboxEntry(
    val id: Long,
    val notification: Notification,
    val pushKind: PushKind?,
)

/** 수강편람 동기화의 남은 일과 알림 outbox. 테이블 설명은 `V6__sugang_sync_pending.sql`. */
@Repository
class SugangSyncPendingRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    private val named = NamedParameterJdbcTemplate(jdbcTemplate)

    /** 강의 변경을 넣는다. [PendingLecture.id]는 쓰지 않는다. */
    fun insertLectures(changes: List<PendingLecture>) {
        if (changes.isEmpty()) return
        val now = now()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO sugang_sync_pending_lecture (lecture_id, class_times_changed, changed_labels, changed_labels_en, created_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            changes,
            BATCH_SIZE,
        ) { ps, change ->
            ps.setLong(1, change.lectureId)
            ps.setBoolean(2, change.classTimesChanged)
            ps.setString(3, change.changedLabels.ko)
            ps.setString(4, change.changedLabels.en)
            ps.setObject(5, now)
        }
    }

    /** 남은 강의 변경을 넣은 순서(엑셀 행 순서)대로. */
    fun findLectures(): List<PendingLecture> =
        jdbcTemplate.query(
            "SELECT id, lecture_id, class_times_changed, changed_labels, changed_labels_en, expanded FROM sugang_sync_pending_lecture ORDER BY id",
        ) { rs, _ ->
            PendingLecture(
                id = rs.getLong("id"),
                lectureId = rs.getLong("lecture_id"),
                classTimesChanged = rs.getBoolean("class_times_changed"),
                changedLabels = LocalizedMessage(ko = rs.getString("changed_labels"), en = rs.getString("changed_labels_en")),
                expanded = rs.getBoolean("expanded"),
            )
        }

    fun markExpanded(pendingLectureIds: Collection<Long>) {
        if (pendingLectureIds.isEmpty()) return
        named.update("UPDATE sugang_sync_pending_lecture SET expanded = TRUE WHERE id IN (:ids)", mapOf("ids" to pendingLectureIds))
    }

    fun findTimetablePairs(pendingLectureIds: Collection<Long>): Set<Pair<Long, Long>> {
        if (pendingLectureIds.isEmpty()) return emptySet()
        return named
            .query(
                "SELECT pending_lecture_id, timetable_id FROM sugang_sync_pending_timetable WHERE pending_lecture_id IN (:ids)",
                mapOf("ids" to pendingLectureIds),
            ) { rs, _ -> rs.getLong("pending_lecture_id") to rs.getLong("timetable_id") }
            .toSet()
    }

    fun insertTimetables(timetables: List<PendingTimetable>) {
        if (timetables.isEmpty()) return
        jdbcTemplate.batchUpdate(
            "INSERT INTO sugang_sync_pending_timetable (pending_lecture_id, timetable_id, notify) VALUES (?, ?, ?)",
            timetables,
            BATCH_SIZE,
        ) { ps, pending ->
            ps.setLong(1, pending.pendingLectureId)
            ps.setLong(2, pending.timetableId)
            ps.setBoolean(3, pending.notify)
        }
    }

    fun findUnprocessedTimetableIds(): List<Long> =
        jdbcTemplate
            .queryForList(
                "SELECT DISTINCT timetable_id FROM sugang_sync_pending_timetable WHERE processed = FALSE ORDER BY timetable_id",
                Long::class.java,
            ).filterNotNull()

    fun findUnprocessedByTimetableIdIn(timetableIds: Collection<Long>): List<PendingTimetable> {
        if (timetableIds.isEmpty()) return emptyList()
        return named.query(
            """
            SELECT pending_lecture_id, timetable_id, notify FROM sugang_sync_pending_timetable
            WHERE processed = FALSE AND timetable_id IN (:ids)
            """,
            mapOf("ids" to timetableIds),
        ) { rs, _ -> PendingTimetable(rs.getLong("pending_lecture_id"), rs.getLong("timetable_id"), rs.getBoolean("notify")) }
    }

    fun markProcessed(timetableIds: Collection<Long>) {
        if (timetableIds.isEmpty()) return
        named.update(
            "UPDATE sugang_sync_pending_timetable SET processed = TRUE WHERE processed = FALSE AND timetable_id IN (:ids)",
            mapOf("ids" to timetableIds),
        )
    }

    /** 반영하지 못한 시간표가 남은 강의 변경. */
    fun findLectureIdsWithUnprocessed(pendingLectureIds: Collection<Long>): Set<Long> {
        if (pendingLectureIds.isEmpty()) return emptySet()
        return named
            .queryForList(
                "SELECT DISTINCT pending_lecture_id FROM sugang_sync_pending_timetable WHERE processed = FALSE AND pending_lecture_id IN (:ids)",
                mapOf("ids" to pendingLectureIds),
                Long::class.java,
            ).filterNotNull()
            .toSet()
    }

    fun deleteLectures(pendingLectureIds: Collection<Long>) {
        if (pendingLectureIds.isEmpty()) return
        val params = mapOf("ids" to pendingLectureIds)
        named.update("DELETE FROM sugang_sync_pending_timetable WHERE pending_lecture_id IN (:ids)", params)
        named.update("DELETE FROM sugang_sync_pending_lecture WHERE id IN (:ids)", params)
    }

    fun insertOutbox(entries: List<Pair<Notification, PushKind?>>) {
        if (entries.isEmpty()) return
        val now = now()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO sugang_sync_outbox (user_id, title, title_en, message, message_en, type, deeplink, push_kind, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            entries,
            BATCH_SIZE,
        ) { ps, (notification, pushKind) ->
            ps.setLong(1, checkNotNull(notification.userId))
            ps.setString(2, notification.title)
            ps.setString(3, notification.titleEn)
            ps.setString(4, notification.message)
            ps.setString(5, notification.messageEn)
            ps.setString(6, notification.type.name)
            ps.setString(7, notification.deeplink)
            ps.setString(8, pushKind?.name)
            ps.setObject(9, now)
        }
    }

    /** 먼저 넣은 순서로 [limit]개. */
    fun findOutbox(limit: Int): List<OutboxEntry> =
        jdbcTemplate.query(
            "SELECT id, user_id, title, title_en, message, message_en, type, deeplink, push_kind FROM sugang_sync_outbox ORDER BY id LIMIT ?",
            { rs, _ ->
                OutboxEntry(
                    id = rs.getLong("id"),
                    notification =
                        Notification(
                            userId = rs.getLong("user_id"),
                            title = rs.getString("title"),
                            titleEn = rs.getString("title_en"),
                            message = rs.getString("message"),
                            messageEn = rs.getString("message_en"),
                            type = NotificationType.valueOf(rs.getString("type")),
                            deeplink = rs.getString("deeplink"),
                        ),
                    pushKind = rs.getString("push_kind")?.let(PushKind::valueOf),
                )
            },
            limit,
        )

    fun deleteOutbox(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        named.update("DELETE FROM sugang_sync_outbox WHERE id IN (:ids)", mapOf("ids" to ids))
    }

    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    private companion object {
        const val BATCH_SIZE = 1_000
    }
}
