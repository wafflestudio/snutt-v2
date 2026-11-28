package com.wafflestudio.snutt.core.domain.campusmap.service

import com.wafflestudio.snutt.core.domain.campusmap.routing.CampusMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class CampusMapProvider(
    private val loader: CampusMapLoader,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var current: CampusMap? = null

    @Volatile
    private var checkedAtNanos = 0L

    fun current(): CampusMap {
        val map = current
        if (map != null && System.nanoTime() - checkedAtNanos <= CHECK_INTERVAL.toNanos()) return map
        return refreshIfStale()
    }

    @Synchronized
    fun refresh(): CampusMap {
        checkedAtNanos = System.nanoTime()
        val map = current
        if (map != null && map.version == loader.currentVersion()) return map
        val next = loader.load()
        current = next
        log.info("캠퍼스 지도 버전 {}을 읽었다 (정점 {}, 간선 {})", next.version, next.graph.nodeCount, next.graph.edgeCount)
        return next
    }

    @Synchronized
    private fun refreshIfStale(): CampusMap {
        val map = current
        if (map != null && System.nanoTime() - checkedAtNanos <= CHECK_INTERVAL.toNanos()) return map
        return refresh()
    }

    companion object {
        private val CHECK_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
