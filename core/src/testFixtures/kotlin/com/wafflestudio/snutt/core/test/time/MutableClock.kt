package com.wafflestudio.snutt.core.test.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 테스트에서 시각을 고정·이동하는 Clock. 통합테스트 컨텍스트의 `Clock` 빈이 이것으로 바뀐다.
 *
 * 기본은 시스템 시각을 그대로 따른다(Hibernate `@CreationTimestamp`·DB `NOW()`와 어긋나지 않게).
 * [setInstant]·[setKst]로 고정하면 [advance]로만 움직이고, [reset]으로 시스템 시각에 다시 붙는다.
 * `withZone`으로 만든 Clock도 같은 시각 상태를 공유한다.
 */
class MutableClock private constructor(
    private val state: State,
    private val zone: ZoneId,
) : Clock() {
    constructor(zone: ZoneId = ZoneOffset.UTC) : this(State(), zone)

    private class State {
        @Volatile
        var frozenAt: Instant? = null
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(state, zone)

    override fun instant(): Instant = state.frozenAt ?: Instant.now()

    val isFrozen: Boolean get() = state.frozenAt != null

    fun setInstant(instant: Instant) {
        state.frozenAt = instant
    }

    /** KST 벽시계 시각으로 고정한다. 예: `setKst(LocalDateTime.of(2026, 9, 1, 0, 0))`은 2026-08-31T15:00Z다. */
    fun setKst(dateTime: LocalDateTime) = setInstant(dateTime.atZone(KST).toInstant())

    /** 현재 시각에서 [duration]만큼 옮기고 그 시각으로 고정한다. 음수면 과거로 간다. */
    fun advance(duration: Duration) = setInstant(instant().plus(duration))

    fun reset() {
        state.frozenAt = null
    }

    fun nowKst(): ZonedDateTime = ZonedDateTime.ofInstant(instant(), KST)

    override fun equals(other: Any?): Boolean = other is MutableClock && other.state === state && other.zone == zone

    override fun hashCode(): Int = System.identityHashCode(state) * 31 + zone.hashCode()

    override fun toString(): String = "MutableClock[${state.frozenAt ?: "system"}, $zone]"

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
