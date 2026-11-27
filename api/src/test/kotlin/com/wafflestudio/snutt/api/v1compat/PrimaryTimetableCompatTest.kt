package com.wafflestudio.snutt.api.v1compat

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant

/**
 * 대표 시간표 지정·해제(parity B7). v1은 isPrimary만 바꿔 저장해 updatedAt을 그대로 뒀다(TimetableServiceImpl.setPrimary의 copy).
 * 구앱은 `/v1/tables/recent`(updated_at이 가장 최근인 시간표)를 처음에 열므로, 대표를 바꿨다고 처음 여는 시간표가 바뀌면 안 된다.
 * v2 엔드포인트도 같은 서비스를 쓰므로 함께 본다.
 */
class PrimaryTimetableCompatTest : AbstractV1CompatTest() {
    @Autowired
    lateinit var userRepository: UserRepository

    private val editedAt = Instant.parse("2026-08-20T03:30:00Z")
    private val olderAt = Instant.parse("2026-08-01T00:00:00Z")

    private lateinit var legacyToken: String
    private lateinit var main: Timetable
    private lateinit var planB: Timetable

    @BeforeEach
    fun seed() {
        legacyToken = v1Register("primaryuser").first
        val user = userRepository.findByLocalIdAndActiveTrue("primaryuser")!!
        val spring = fixtures.coursebook(2026, Semester.SPRING)
        main = fixtures.timetable(user, spring, title = "본 시간표", isPrimary = true)
        planB = fixtures.timetable(user, spring, title = "플랜 B", isPrimary = false)
        setUpdatedAt(main, editedAt)
        setUpdatedAt(planB, olderAt)
    }

    private fun setUpdatedAt(
        timetable: Timetable,
        at: Instant,
    ) {
        jdbcTemplate.update("UPDATE timetable SET updated_at = ? WHERE id = ?", Timestamp.from(at), timetable.id)
    }

    private fun stored(timetable: Timetable): Pair<Boolean, Instant> =
        jdbcTemplate.queryForObject("SELECT is_primary, updated_at FROM timetable WHERE id = ?", { rs, _ ->
            rs.getBoolean("is_primary") to rs.getTimestamp("updated_at").toInstant()
        }, timetable.id)!!

    private fun recentTitle(): String = body(v1Get("/v1/tables/recent", legacyToken))["title"].asString()

    @Test
    fun `구앱이 대표를 바꿔도 두 시간표의 수정 시각과 처음 여는 시간표는 그대로다`() {
        assertEquals("본 시간표", recentTitle())

        assertEquals(200, v1Post("/v1/tables/${planB.id}/primary", legacyToken = legacyToken).statusCode.value())

        assertEquals(false to editedAt, stored(main))
        assertEquals(true to olderAt, stored(planB))
        assertEquals("본 시간표", recentTitle())
        assertEquals(editedAt, Instant.parse(body(v1Get("/v1/tables/${main.id}", legacyToken))["updated_at"].asString()))
    }

    @Test
    fun `구앱이 대표를 해제해도 수정 시각은 그대로다`() {
        assertEquals(200, v1Delete("/v1/tables/${main.id}/primary", legacyToken).statusCode.value())

        assertEquals(false to editedAt, stored(main))
    }

    @Test
    fun `신앱의 대표 지정·해제도 수정 시각을 바꾸지 않는다`() {
        val v2Token = v2Login("primaryuser")

        val set = put("/v2/timetables/${planB.id}/primary", token = v2Token)
        assertEquals(200, set.statusCode.value(), "body=${set.body}")
        val primaryIds = body(set).items().filter { it["isPrimary"].asBoolean() }.map { it["id"].asLong() }
        assertEquals(listOf(planB.id), primaryIds, "body=${set.body}")
        assertEquals(false to editedAt, stored(main))
        assertEquals(true to olderAt, stored(planB))

        assertEquals(200, delete("/v2/timetables/${planB.id}/primary", v2Token).statusCode.value())
        assertEquals(false to olderAt, stored(planB))
    }
}
