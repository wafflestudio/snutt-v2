package com.wafflestudio.snutt.parity.compare

/**
 * `kind: order` 허용 항목을 적용한다. 대상 배열을 양쪽 모두 sortBy 키로 안정 정렬하고, 정렬 전 키 순서가 양쪽에서
 * 달랐으면 ORDER 차이 하나를 남긴다. 정렬 결과끼리의 나머지 비교는 [JsonDiff]가 한다.
 */
object Reorder {
    /** order 규칙이면 정렬, element 규칙이면 해당 원소 제거. */
    fun applyRule(
        rule: AllowedDiff,
        v1: Any?,
        v2: Any?,
        allowed: AllowedDiffs,
    ): Pair<Result, List<Diff>> {
        if (rule.element == null) {
            val result = apply(rule, v1, v2)
            return result to listOfNotNull(result.diff)
        }
        val onV1 = rule.kind == DiffKind.ONLY_V1.code
        val target = if (onV1) v1 else v2
        val list = select(target, rule.path) as? List<*> ?: return Result(v1, v2, null) to emptyList()
        val diffs = mutableListOf<Diff>()
        val kept =
            list.filterIndexed { index, item ->
                val drop = allowed.sameElement(rule, item)
                if (drop) {
                    diffs +=
                        Diff(
                            "${rule.path}[$index]",
                            if (onV1) DiffKind.ONLY_V1 else DiffKind.ONLY_V2,
                            if (onV1) item else null,
                            if (onV1) null else item,
                        )
                }
                !drop
            }
        val replaced = replace(target, rule.path, kept)
        return (if (onV1) Result(replaced, v2, null) else Result(v1, replaced, null)) to diffs
    }

    class Result(
        val v1: Any?,
        val v2: Any?,
        val diff: Diff?,
    )

    fun apply(
        rule: AllowedDiff,
        v1: Any?,
        v2: Any?,
    ): Result {
        val keys = rule.sortBy.orEmpty()
        val list1 = select(v1, rule.path) as? List<*>
        val list2 = select(v2, rule.path) as? List<*>
        if (list1 == null || list2 == null) return Result(v1, v2, null)
        val sorted1 = list1.sortedWith(comparator(keys))
        val sorted2 = list2.sortedWith(comparator(keys))
        val order1 = list1.map { keyOf(it, keys) }
        val order2 = list2.map { keyOf(it, keys) }
        val diff = if (order1 != order2) Diff(rule.path, DiffKind.ORDER, order1, order2) else null
        return Result(replace(v1, rule.path, sorted1), replace(v2, rule.path, sorted2), diff)
    }

    /** 키는 점으로 하위 필드를 가리킬 수 있다(`nickname.tag`). */
    private fun keyOf(
        item: Any?,
        keys: List<String>,
    ): List<Any?> = keys.map { key -> key.split('.').fold(item) { node, part -> (node as? Map<*, *>)?.get(part) } }

    private fun comparator(keys: List<String>): Comparator<Any?> =
        Comparator { a, b ->
            keyOf(a, keys).zip(keyOf(b, keys)).firstNotNullOfOrNull { (x, y) -> compare(x, y).takeIf { it != 0 } } ?: 0
        }

    private fun compare(
        a: Any?,
        b: Any?,
    ): Int =
        when {
            a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
            else -> a.toString().compareTo(b.toString())
        }

    /** `$` 또는 `$.a.b`만 지원한다. */
    private fun segments(path: String): List<String> {
        require(path == "$" || path.startsWith("$.")) { "order 경로는 \$ 또는 \$.a.b 형식: $path" }
        return path.removePrefix("$").split('.').filter { it.isNotEmpty() }
    }

    private fun select(
        root: Any?,
        path: String,
    ): Any? = segments(path).fold(root) { node, key -> (node as? Map<*, *>)?.get(key) }

    private fun replace(
        root: Any?,
        path: String,
        value: Any?,
    ): Any? {
        val keys = segments(path)
        if (keys.isEmpty()) return value

        fun go(
            node: Any?,
            index: Int,
        ): Any? {
            val map = node as? Map<*, *> ?: return node
            val key = keys[index]
            return map.entries.associate { (k, v) ->
                k.toString() to
                    if (k == key) (if (index == keys.lastIndex) value else go(v, index + 1)) else v
            }
        }
        return go(root, 0)
    }
}
