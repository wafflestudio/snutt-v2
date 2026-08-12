package com.wafflestudio.snutt.migration

import org.springframework.stereotype.Component

// ObjectId(24-hex) → 신 MySQL BIGINT 매핑. lecture는 스트리밍이라 제외하고,
// 행 수가 적은 리소스만 인메모리로 보관한다 (PLAN.md §5)
@Component
class IdMaps {
    private val maps = mutableMapOf<String, MutableMap<String, Long>>()

    fun put(
        resource: String,
        oldId: String,
        newId: Long,
    ) {
        maps.getOrPut(resource) { mutableMapOf() }[oldId] = newId
    }

    fun get(
        resource: String,
        oldId: String,
    ): Long? = maps[resource]?.get(oldId)

    fun size(resource: String): Int = maps[resource]?.size ?: 0
}
