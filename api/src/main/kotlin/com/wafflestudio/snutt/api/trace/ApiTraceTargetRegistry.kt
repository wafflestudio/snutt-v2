package com.wafflestudio.snutt.api.trace

import com.wafflestudio.snutt.core.domain.trace.event.ApiTraceTargetsChangedEvent
import com.wafflestudio.snutt.core.domain.trace.repository.ApiTraceTargetRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ApiTraceTargetRegistry(
    private val apiTraceTargetRepository: ApiTraceTargetRepository,
) {
    @Volatile
    private var targetUserIds: Set<Long> = emptySet()

    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        refresh()
    }

    @EventListener(ApiTraceTargetsChangedEvent::class)
    fun onTargetsChanged(event: ApiTraceTargetsChangedEvent) {
        refresh()
    }

    @Scheduled(fixedDelay = REFRESH_INTERVAL_MILLIS)
    fun refresh() {
        targetUserIds = apiTraceTargetRepository.findAll().map { it.userId }.toSet()
    }

    fun isTarget(userId: Long): Boolean = userId in targetUserIds

    companion object {
        private const val REFRESH_INTERVAL_MILLIS = 60_000L
    }
}
