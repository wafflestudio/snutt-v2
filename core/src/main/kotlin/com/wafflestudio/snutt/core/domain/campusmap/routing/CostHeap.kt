package com.wafflestudio.snutt.core.domain.campusmap.routing

internal class CostHeap(
    initialCapacity: Int = 64,
) {
    private var costs = DoubleArray(initialCapacity)
    private var states = IntArray(initialCapacity)
    private var size = 0

    fun isNotEmpty(): Boolean = size > 0

    fun push(
        cost: Double,
        state: Int,
    ) {
        if (size == costs.size) {
            costs = costs.copyOf(size * 2)
            states = states.copyOf(size * 2)
        }
        var i = size++
        while (i > 0) {
            val parent = (i - 1) shr 1
            if (costs[parent] <= cost) break
            costs[i] = costs[parent]
            states[i] = states[parent]
            i = parent
        }
        costs[i] = cost
        states[i] = state
    }

    fun peekCost(): Double = costs[0]

    fun pop(): Int {
        val top = states[0]
        val lastCost = costs[--size]
        val lastState = states[size]
        var i = 0
        while (true) {
            val left = 2 * i + 1
            if (left >= size) break
            val child = if (left + 1 < size && costs[left + 1] < costs[left]) left + 1 else left
            if (costs[child] >= lastCost) break
            costs[i] = costs[child]
            states[i] = states[child]
            i = child
        }
        costs[i] = lastCost
        states[i] = lastState
        return top
    }
}
