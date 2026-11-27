package com.wafflestudio.snutt.migration.rehearsal.check

import org.springframework.jdbc.core.JdbcTemplate

/**
 * 이관 뒤 v2 DB의 도메인 불변식을 SQL로 점검한다. 각 항목은 위반 행 수를 센다.
 *
 * - FK·유일 제약: information_schema에서 모든 제약을 읽어 직접 센다. 이관은 FK 검사를 켠 채 넣지만, TRUNCATE·DELETE 경로나
 *   제약을 나중에 추가하는 변경이 있어도 같은 점검으로 잡히게 일반화했다.
 * - 도메인 규칙: DB 제약으로 표현되지 않은 것(대표 시간표 하나, 테마 소유자, 탈퇴 사용자의 활성 데이터 등).
 * - 시간 겹침: v1 원본에도 겹친 시간표가 있을 수 있으므로(구버전 앱이 막지 않던 시기) 0이 아니라 v1 원본의 겹친 시간표 수와 같아야 한다.
 */
class Invariants(
    private val jdbc: JdbcTemplate,
) {
    class Invariant(
        val name: String,
        val sql: String,
        /** null이면 0이어야 한다. */
        val expected: Long? = null,
    )

    class Violation(
        val invariant: Invariant,
        val count: Long,
    ) {
        val expected get() = invariant.expected ?: 0L
        val passed get() = count == expected
    }

    fun check(v1OverlappingTimetables: Long): List<Violation> =
        (foreignKeys() + uniqueKeys() + domainRules(v1OverlappingTimetables)).map { invariant ->
            Violation(invariant, jdbc.queryForObject(invariant.sql, Long::class.java) ?: 0L)
        }

    private fun foreignKeys(): List<Invariant> =
        jdbc
            .query(
                """
                SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME, CONSTRAINT_NAME
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL
                ORDER BY TABLE_NAME, CONSTRAINT_NAME
                """.trimIndent(),
            ) { rs, _ ->
                val (table, column, parent, parentColumn) = (1..4).map { rs.getString(it) }
                Invariant(
                    "FK ${rs.getString(5)}: $table.$column → $parent.$parentColumn",
                    "SELECT COUNT(*) FROM `$table` c LEFT JOIN `$parent` p ON p.`$parentColumn` = c.`$column` " +
                        "WHERE c.`$column` IS NOT NULL AND p.`$parentColumn` IS NULL",
                )
            }

    private fun uniqueKeys(): List<Invariant> {
        val keys = LinkedHashMap<Pair<String, String>, MutableList<String>>()
        jdbc.query(
            """
            SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY' AND TABLE_NAME <> 'flyway_schema_history'
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """.trimIndent(),
        ) { rs -> keys.getOrPut(rs.getString(1) to rs.getString(2)) { mutableListOf() } += rs.getString(3) }
        return keys.map { (key, columns) ->
            val (table, index) = key
            val cols = columns.joinToString(",") { "`$it`" }
            val notNull = columns.joinToString(" AND ") { "`$it` IS NOT NULL" }
            Invariant(
                "UNIQUE $table.$index(${columns.joinToString()})",
                "SELECT COUNT(*) FROM (SELECT 1 FROM `$table` WHERE $notNull GROUP BY $cols HAVING COUNT(*) > 1) d",
            )
        }
    }

    private fun domainRules(v1OverlappingTimetables: Long): List<Invariant> =
        listOf(
            Invariant(
                "(사용자, 학기)마다 대표 시간표는 하나",
                "SELECT COUNT(*) FROM (SELECT 1 FROM timetable WHERE is_primary GROUP BY user_id, year, semester HAVING COUNT(*) > 1) d",
            ),
            Invariant(
                "시간표 테마는 내장 테마이거나 시간표 주인의 테마",
                "SELECT COUNT(*) FROM timetable t JOIN timetable_theme th ON th.id = t.theme_id " +
                    "WHERE th.builtin_code IS NULL AND th.user_id <> t.user_id",
            ),
            Invariant(
                "기본 테마는 내장 테마이거나 그 사용자의 테마",
                "SELECT COUNT(*) FROM user_preference p JOIN timetable_theme th ON th.id = p.default_theme_id " +
                    "WHERE th.builtin_code IS NULL AND th.user_id <> p.user_id",
            ),
            Invariant(
                "시간표 강의의 팔레트 번호는 테마 팔레트 안",
                "SELECT COUNT(*) FROM timetable_lecture tl JOIN timetable t ON t.id = tl.timetable_id " +
                    "JOIN timetable_theme th ON th.id = t.theme_id WHERE tl.palette_index >= JSON_LENGTH(th.colors)",
            ),
            Invariant(
                "직접 만든 강의는 제목과 수업 시간을 override에 가진다",
                "SELECT COUNT(*) FROM timetable_lecture WHERE lecture_id IS NULL AND (overrides IS NULL OR " +
                    "NOT JSON_CONTAINS_PATH(overrides, 'all', '$.courseTitle', '$.classPlaceAndTimes'))",
            ),
            Invariant(
                "사용자 지정 색은 배경·글자색을 모두 가진다",
                "SELECT COUNT(*) FROM timetable_lecture WHERE custom_color IS NOT NULL AND " +
                    "NOT JSON_CONTAINS_PATH(custom_color, 'all', '$.backgroundColor', '$.foregroundColor')",
            ),
            Invariant(
                "시간표 안에서 시간이 겹치는 강의가 있는 시간표 수 = v1 원본과 같음",
                OVERLAP_SQL,
                expected = v1OverlappingTimetables,
            ),
            Invariant("강의마다 과목이 있다", "SELECT COUNT(*) FROM lecture WHERE course_id IS NULL"),
            Invariant(
                "과목의 최신 강의는 그 과목의 강의",
                "SELECT COUNT(*) FROM course c JOIN lecture l ON l.id = c.latest_lecture_id WHERE l.course_id <> c.id",
            ),
            Invariant(
                "과목의 강의평 수는 숨기지 않은 강의평 수",
                "SELECT COUNT(*) FROM course c WHERE c.eval_count <> " +
                    "(SELECT COUNT(*) FROM evaluation e WHERE e.course_id = c.id AND e.is_hidden = FALSE)",
            ),
            Invariant(
                "강의평 공감 수는 공감 행 수",
                "SELECT COUNT(*) FROM evaluation e WHERE e.like_count <> (SELECT COUNT(*) FROM evaluation_like l WHERE l.evaluation_id = e.id)",
            ),
            Invariant("친구 관계의 두 사용자는 다르다", "SELECT COUNT(*) FROM friend WHERE from_user_id = to_user_id"),
            Invariant(
                "친구 관계의 두 사용자는 활성",
                "SELECT COUNT(*) FROM friend f JOIN `user` a ON a.id = f.from_user_id JOIN `user` b ON b.id = f.to_user_id " +
                    "WHERE NOT a.active OR NOT b.active",
            ),
            Invariant(
                "활성 기기의 사용자는 활성",
                "SELECT COUNT(*) FROM user_device d JOIN `user` u ON u.id = d.user_id WHERE d.is_deleted = FALSE AND NOT u.active",
            ),
            Invariant(
                "활성 기기는 FCM 토큰이 비어 있지 않다",
                "SELECT COUNT(*) FROM user_device WHERE is_deleted = FALSE AND TRIM(fcm_registration_id) = ''",
            ),
            Invariant(
                "소셜 연동·구 토큰은 활성 사용자만",
                "SELECT (SELECT COUNT(*) FROM user_social_auth s JOIN `user` u ON u.id = s.user_id WHERE NOT u.active) + " +
                    "(SELECT COUNT(*) FROM legacy_access_token t JOIN `user` u ON u.id = t.user_id WHERE NOT u.active)",
            ),
            Invariant(
                "다운로드 테마의 이름·색은 게시 스냅샷과 같다",
                "SELECT COUNT(*) FROM timetable_theme t JOIN published_theme p ON p.id = t.publication_id " +
                    "WHERE t.name <> p.name OR JSON_EXTRACT(t.colors, '$') <> JSON_EXTRACT(p.colors, '$')",
            ),
            Invariant(
                "알림 deeplink에 v1 ObjectId가 없다",
                "SELECT COUNT(*) FROM notification WHERE deeplink REGEXP '[0-9a-fA-F]{24}'",
            ),
            Invariant(
                "수업 시간은 0~6요일, 시작 < 끝, 0~1440분",
                "SELECT COUNT(*) FROM lecture_class_time WHERE day NOT BETWEEN 0 AND 6 OR start_minute >= end_minute " +
                    "OR start_minute < 0 OR end_minute > 1440",
            ),
            Invariant(
                "리마인더 스케줄은 0~6요일, 0~1439분",
                "SELECT COUNT(*) FROM timetable_lecture_reminder_schedule WHERE day NOT BETWEEN 0 AND 6 OR minute NOT BETWEEN 0 AND 1439",
            ),
        )

    companion object {
        /**
         * 시간표 강의의 실제 수업 시간: override에 classPlaceAndTimes가 있으면 그것, 없으면 강의의 수업 시간.
         * 같은 시간표의 서로 다른 강의가 같은 요일에 겹치면 그 시간표를 센다.
         */
        val OVERLAP_SQL =
            """
            WITH slot AS (
                SELECT tl.timetable_id, tl.id AS tl_id, jt.day, jt.start_minute, jt.end_minute
                FROM timetable_lecture tl
                JOIN JSON_TABLE(tl.overrides, '$.classPlaceAndTimes[*]' COLUMNS (
                    day INT PATH '$.day', start_minute INT PATH '$.startMinute', end_minute INT PATH '$.endMinute')) jt
                WHERE JSON_CONTAINS_PATH(tl.overrides, 'one', '$.classPlaceAndTimes')
                UNION ALL
                SELECT tl.timetable_id, tl.id, ct.day, ct.start_minute, ct.end_minute
                FROM timetable_lecture tl JOIN lecture_class_time ct ON ct.lecture_id = tl.lecture_id
                WHERE tl.overrides IS NULL OR NOT JSON_CONTAINS_PATH(tl.overrides, 'one', '$.classPlaceAndTimes')
            )
            SELECT COUNT(DISTINCT a.timetable_id) FROM slot a JOIN slot b
              ON a.timetable_id = b.timetable_id AND a.tl_id < b.tl_id AND a.day = b.day
             AND a.start_minute < b.end_minute AND b.start_minute < a.end_minute
            """.trimIndent()
    }
}
