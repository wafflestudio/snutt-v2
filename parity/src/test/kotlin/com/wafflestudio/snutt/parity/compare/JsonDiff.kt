package com.wafflestudio.snutt.parity.compare

import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.math.BigInteger

enum class DiffKind(
    val code: String,
) {
    /** 양쪽에 있는데 값이나 JSON 타입이 다르다. */
    CHANGED("changed"),

    /** v1에만 있다(v2 응답에서 빠짐). */
    ONLY_V1("only-v1"),

    /** v2에만 있다(v2 응답에 더해짐). */
    ONLY_V2("only-v2"),

    /** 배열 원소 순서가 다르다. 허용 항목의 sortBy로 안정 정렬하면 같아지는 경우만 이 종류로 보고된다. */
    ORDER("order"),
    ;

    companion object {
        fun of(code: String) = entries.first { it.code == code }
    }
}

data class Diff(
    /** `status`, `header:Content-Type`, 또는 바디 경로 `$.lecture_list[0].color`. */
    val path: String,
    val kind: DiffKind,
    val v1: Any?,
    val v2: Any?,
) {
    fun describe(): String =
        when (kind) {
            DiffKind.CHANGED -> "$path: v1=${render(v1)} v2=${render(v2)}"
            DiffKind.ONLY_V1 -> "$path: v1에만 있음 (${render(v1)})"
            DiffKind.ONLY_V2 -> "$path: v2에만 있음 (${render(v2)})"
            DiffKind.ORDER -> "$path: 순서가 다름 v1=${render(v1)} v2=${render(v2)}"
        }

    private fun render(value: Any?): String {
        val text = JsonBodies.write(value)
        return if (text.length > 200) text.take(200) + "…" else text
    }
}

object JsonDiff {
    fun diff(
        v1: Any?,
        v2: Any?,
        path: String = "$",
    ): List<Diff> {
        val out = mutableListOf<Diff>()
        walk(path, v1, v2, out)
        return out
    }

    private fun walk(
        path: String,
        a: Any?,
        b: Any?,
        out: MutableList<Diff>,
    ) {
        when {
            a is Map<*, *> && b is Map<*, *> -> {
                val keys =
                    LinkedHashSet<String>().apply {
                        a.keys.forEach { add(it.toString()) }
                        b.keys.forEach { add(it.toString()) }
                    }
                keys.forEach { key ->
                    val child = "$path.$key"
                    when {
                        !b.containsKey(key) -> out += Diff(child, DiffKind.ONLY_V1, a[key], null)
                        !a.containsKey(key) -> out += Diff(child, DiffKind.ONLY_V2, null, b[key])
                        else -> walk(child, a[key], b[key], out)
                    }
                }
            }
            a is List<*> && b is List<*> -> {
                for (i in 0 until maxOf(a.size, b.size)) {
                    val child = "$path[$i]"
                    when {
                        i >= b.size -> out += Diff(child, DiffKind.ONLY_V1, a[i], null)
                        i >= a.size -> out += Diff(child, DiffKind.ONLY_V2, null, b[i])
                        else -> walk(child, a[i], b[i], out)
                    }
                }
            }
            a != b -> out += Diff(path, DiffKind.CHANGED, a, b)
        }
    }
}

object JsonBodies {
    private val mapper: JsonMapper = JsonMapper.builder().build()

    /** JSON이 아니면 문자열 그대로 둔다(HTML 정적 페이지, 빈 바디). 숫자는 정수 Long / 실수 Double로 맞춘다. */
    fun parse(body: String): Any? {
        if (body.isBlank()) return null
        val parsed = runCatching { mapper.readValue(body, Any::class.java) }.getOrElse { return body }
        return canonical(parsed)
    }

    fun write(value: Any?): String = mapper.writeValueAsString(value)

    fun pretty(value: Any?): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)

    private fun canonical(node: Any?): Any? =
        when (node) {
            is Map<*, *> -> node.entries.associate { (k, v) -> k.toString() to canonical(v) }
            is List<*> -> node.map(::canonical)
            is Int, is Short, is Byte -> (node as Number).toLong()
            // Long 범위를 넘는 정수는 잘라 같게 만들지 않도록 그대로 둔다.
            is BigInteger -> runCatching { node.longValueExact() }.getOrDefault(node)
            is Float, is BigDecimal -> (node as Number).toDouble()
            else -> node
        }
}
