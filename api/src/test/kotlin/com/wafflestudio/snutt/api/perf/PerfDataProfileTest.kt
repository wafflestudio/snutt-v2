package com.wafflestudio.snutt.api.perf

import com.wafflestudio.snutt.core.test.perf.PerfDataGenerator
import com.wafflestudio.snutt.core.test.perf.PerfDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 생성기가 docs/perf-data-profile.md의 분포를 지키는지 본다. 분포가 어긋나면 실행계획 테스트가 운영과 다른 데이터를 보고 통과할 수 있다.
 * 값은 seed가 고정이라 매번 같다. 범위로 쓴 것은 운영 분위수에 맞춘 허용 구간이다.
 */
class PerfDataProfileTest : AbstractPerfTest() {
    private fun long(sql: String): Long =
        PerfDatabase.connection.createStatement().use { st ->
            st.executeQuery(sql).use {
                it.next()
                it.getLong(1)
            }
        }

    private fun quantile(
        sql: String,
        q: Double,
    ): Long {
        val values =
            PerfDatabase.connection
                .createStatement()
                .use { st ->
                    st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
                }.sorted()
        return values[((values.size - 1) * q).toInt()]
    }

    @Test
    fun `운영 분포를 줄인 데이터셋이 만들어진다`() {
        println("perf 데이터셋 생성 ${perf.generationMillis}ms, 행 수 ${perf.rowCounts}")
        assertEquals(PerfDataGenerator.USERS.toLong(), long("SELECT COUNT(*) FROM `user`"))
        assertEquals(PerfDataGenerator.EVALUATIONS.toLong(), long("SELECT COUNT(*) FROM evaluation"))
        assertEquals(PerfDataGenerator.EVALUATED_COURSES.toLong(), long("SELECT COUNT(DISTINCT course_id) FROM evaluation"))

        // 정규학기 강의 수는 운영 규모(약 6,000개)를 유지한다.
        val currentLectures = long("SELECT COUNT(*) FROM lecture WHERE year = ${perf.current.year} AND semester = ${perf.current.semester}")
        assertTrue(currentLectures in 4_000..9_000, "이번 학기 강의 $currentLectures")

        // 사용자당 시간표: 운영 p50 1, p90 9, 사용자·학기당 평균 1.24.
        val perUser = "SELECT COUNT(*) FROM timetable GROUP BY user_id"
        assertEquals(1, quantile(perUser, 0.5))
        assertTrue(quantile(perUser, 0.9) in 5..13, "p90 ${quantile(perUser, 0.9)}")
        assertEquals(773, long("SELECT COUNT(*) FROM timetable WHERE user_id = ${perf.heavyTimetableUserId}"))

        // 시간표당 강의: 운영 p50 2, p90 8, max 47.
        val perTimetable = "SELECT COUNT(tl.id) FROM timetable t LEFT JOIN timetable_lecture tl ON tl.timetable_id = t.id GROUP BY t.id"
        assertEquals(2, quantile(perTimetable, 0.5))
        assertTrue(quantile(perTimetable, 0.9) in 6..9, "p90 ${quantile(perTimetable, 0.9)}")

        // 과목당 강의평: 운영 p50 2, p90 12, max 212. snuev 이관 계정이 약 94%.
        val perCourse = "SELECT COUNT(*) FROM evaluation GROUP BY course_id"
        assertTrue(quantile(perCourse, 0.5) in 1..3, "p50 ${quantile(perCourse, 0.5)}")
        assertTrue(quantile(perCourse, 0.9) in 9..16, "p90 ${quantile(perCourse, 0.9)}")
        assertEquals(212, long("SELECT COUNT(*) FROM evaluation WHERE course_id = ${perf.popularCourseId}"))
        val snuevShare = long("SELECT COUNT(*) FROM evaluation WHERE user_id = ${perf.snuevUserId}") * 100 / PerfDataGenerator.EVALUATIONS
        assertTrue(snuevShare in 92..96, "snuev 비율 $snuevShare%")

        // 친구: 극단 사용자 115명.
        assertEquals(
            115,
            long("SELECT COUNT(*) FROM friend WHERE is_accepted AND ${perf.heavyFriendUserId} IN (from_user_id, to_user_id)"),
        )
    }
}
