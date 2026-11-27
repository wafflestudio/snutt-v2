package com.wafflestudio.snutt.parity.compare

import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue

/**
 * 의도한 차이 목록(`parity/src/test/resources/parity/allowed-diffs.yml`). 여기 없는 차이는 시나리오를 실패시킨다.
 * 등록 규칙은 docs/parity.md "허용 차이 등록".
 */
data class AllowedDiff(
    /**
     * `GET /v1/tables/{id}`처럼 메서드 + 경로 템플릿. 경로의 `@{별칭}`은 `{id}`로 쓴다.
     * `*`는 아무 문자열(`GET /v1/tables*`, `*`는 모든 엔드포인트). 여러 엔드포인트에 같은 이유가 걸릴 때만 쓴다.
     */
    val endpoint: String,
    /**
     * 차이 경로 glob. `[*]`는 아무 인덱스, `*`는 키 하나, `**`는 0개 이상의 아무 경로, `{a,b}`는 둘 중 하나.
     * 예: `$.lecture_list[*].color`, `$.**.{remark,category}`. `status`, `header:Content-Type`도 쓸 수 있다.
     */
    val path: String,
    /** changed / only-v1 / only-v2 / order. 없으면 종류를 가리지 않는다(order는 명시해야 한다). */
    val kind: String? = null,
    /**
     * kind가 order일 때만. [path]의 배열을 양쪽 모두 이 키들로 안정 정렬한 뒤 비교한다. 키가 같은 원소끼리의 순서는
     * 그대로라 그 안의 순서 차이는 여전히 잡힌다. 클라이언트가 이 키로 다시 정렬한다는 근거가 있을 때만 쓴다.
     */
    val sortBy: List<String>? = null,
    /**
     * 있으면 v1 쪽 값(정규화 후)이 이 값일 때만 허용한다. 넓은 경로를 좁히는 데 쓴다(예: v1이 빈 문자열일 때만).
     * 객체를 주면 적은 키만 비교한다(`{_id: "@lecture:gone"}`).
     */
    val v1: Any? = null,
    /** 있으면 v2 쪽 값(정규화 후)이 이 값일 때만 허용한다. */
    val v2: Any? = null,
    /** true면 양쪽 응답이 모두 4xx·5xx일 때만 허용한다(에러 바디 문구 차이용). */
    val onlyErrors: Boolean = false,
    /** 특정 시나리오에서만 허용할 때(하나 또는 목록). 없으면 그 엔드포인트를 부르는 모든 시나리오. */
    val scenario: List<String>? = null,
    /** 시나리오 안의 특정 요청(label)에서만 허용할 때. [scenario]와 함께 쓴다. */
    val label: String? = null,
    /**
     * kind가 only-v1/only-v2이고 [path]가 배열일 때, 이 키 값들을 가진 원소를 그쪽 배열에서 빼고 나머지를 비교한다
     * (`{id: 317}`). 원소 하나가 끼거나 빠져 뒤 원소의 인덱스가 밀리는 경우에 쓴다. 뺀 원소마다 차이 하나가 남는다.
     */
    val element: Map<String, Any?>? = null,
    val reason: String,
    /** 근거 문서·코드 위치. */
    val ref: String? = null,
) {
    private val endpointRegex: Regex by lazy { endpointGlob(endpoint) }

    /** element 규칙이 남기는 차이는 원소 경로(`$.tags[1]`)에 붙는다. */
    private val pathRegex: Regex by lazy { toRegex(if (element != null) "$path[*]" else path) }

    fun matches(
        scenarioName: String,
        endpointLabel: String,
        exchangeLabel: String,
        errorResponse: Boolean,
        diff: Diff,
    ): Boolean =
        endpointRegex.matches(endpointLabel) &&
            (scenario == null || scenarioName in scenario) &&
            (label == null || label == exchangeLabel) &&
            (kind == null || DiffKind.of(kind) == diff.kind) &&
            (!onlyErrors || errorResponse) &&
            (v1 == null || same(v1, diff.v1)) &&
            (v2 == null || same(v2, diff.v2)) &&
            pathRegex.matches(diff.path)

    private fun same(
        expected: Any,
        actual: Any?,
    ): Boolean {
        val wanted = canonical(expected)
        return if (wanted is Map<*, *> && actual is Map<*, *>) wanted.all { (k, v) -> actual[k] == v } else wanted == actual
    }

    private fun canonical(value: Any?): Any? =
        when (value) {
            is Int, is Short, is Long -> (value as Number).toLong()
            is Float, is Double -> (value as Number).toDouble()
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to canonical(v) }
            is List<*> -> value.map(::canonical)
            else -> value
        }

    companion object {
        /** 엔드포인트 glob: `*`는 아무 문자열, `{a,b}`는 둘 중 하나(`{id}`처럼 쉼표가 없으면 글자 그대로). */
        fun endpointGlob(glob: String): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when (glob[i]) {
                    '*' -> out.append(".*")
                    '{' -> {
                        // `{id}`는 경로 템플릿 자리 표시라 그대로, 쉼표가 있는 `{a,b}`만 선택지로 본다.
                        val close = glob.indexOf('}', i)
                        if (close < 0 || ',' !in glob.substring(i, close)) {
                            out.append(Regex.escape("{"))
                        } else {
                            out.append(glob.substring(i + 1, close).split(',').joinToString("|", "(", ")") { Regex.escape(it.trim()) })
                            i = close
                        }
                    }
                    else -> out.append(Regex.escape(glob[i].toString()))
                }
                i++
            }
            return Regex(out.toString())
        }

        fun toRegex(glob: String): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when {
                    // `.**.`은 0개 이상의 경로 조각: `$.**.remark`가 `$.remark`, `$[0].remark`, `$.a[1].remark`에 맞는다.
                    glob.startsWith(".**", i) -> {
                        out.append("""(?:[.\[].*)?""")
                        i += 3
                    }
                    glob.startsWith("[*]", i) -> {
                        out.append("""\[\d+\]""")
                        i += 3
                    }
                    glob.startsWith("**", i) -> {
                        out.append(".*")
                        i += 2
                    }
                    glob[i] == '*' -> {
                        out.append("""[^.\[]+""")
                        i += 1
                    }
                    glob[i] == '{' -> {
                        val close = glob.indexOf('}', i)
                        require(close > i) { "닫히지 않은 {: $glob" }
                        out.append(glob.substring(i + 1, close).split(',').joinToString("|", "(", ")") { Regex.escape(it.trim()) })
                        i = close + 1
                    }
                    else -> {
                        out.append(Regex.escape(glob[i].toString()))
                        i += 1
                    }
                }
            }
            return Regex(out.toString())
        }
    }
}

class AllowedDiffs(
    val entries: List<AllowedDiff>,
) {
    private val used = HashSet<Int>()

    @Synchronized
    fun find(
        scenario: String,
        endpoint: String,
        exchangeLabel: String,
        errorResponse: Boolean,
        diff: Diff,
    ): AllowedDiff? {
        val index = entries.indexOfFirst { it.matches(scenario, endpoint, exchangeLabel, errorResponse, diff) }
        if (index < 0) return null
        used += index
        return entries[index]
    }

    /** [endpoint]에 적용할 배열 규칙(order, element). 쓰였다고 표시하지는 않는다(실제로 적용됐을 때 [find]가 표시). */
    fun arrayRules(
        scenario: String,
        endpoint: String,
        exchangeLabel: String,
    ): List<AllowedDiff> =
        entries.filter {
            (it.kind == DiffKind.ORDER.code || it.element != null) &&
                it.endpoint == endpoint &&
                (it.scenario == null || scenario in it.scenario) &&
                (it.label == null || it.label == exchangeLabel)
        }

    /** 문자열 조건에 `*`가 있으면 glob으로 본다(`@course-minted:*`). */
    fun sameElement(
        rule: AllowedDiff,
        item: Any?,
    ): Boolean =
        rule.element.orEmpty().all { (k, v) ->
            val actual = (item as? Map<*, *>)?.get(k)
            if (v is String && '*' in v) {
                actual is String && Regex(v.split('*').joinToString(".*") { Regex.escape(it) }).matches(actual)
            } else {
                actual == canonicalValue(v)
            }
        }

    @Synchronized
    fun unused(): List<AllowedDiff> = entries.filterIndexed { index, _ -> index !in used }

    companion object {
        const val RESOURCE = "parity/allowed-diffs.yml"
        const val KNOWN_FAILURES = "parity/known-failures.yml"

        fun canonicalValue(value: Any?): Any? =
            when (value) {
                is Int, is Short, is Long -> (value as Number).toLong()
                is Float, is Double -> (value as Number).toDouble()
                else -> value
            }

        private val mapper =
            YAMLMapper
                .builder()
                .addModule(KotlinModule.Builder().build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build()

        fun load(resource: String = RESOURCE): AllowedDiffs {
            val text =
                checkNotNull(AllowedDiffs::class.java.classLoader.getResource(resource)) { "$resource 없음" }.readText()
            val entries: List<AllowedDiff> = mapper.readValue(text) ?: emptyList()
            entries.forEach { entry ->
                require(entry.reason.isNotBlank()) { "이유가 없는 허용 차이: $entry" }
                require(entry.endpoint == "*" || entry.endpoint.matches(Regex("^(GET|POST|PUT|PATCH|DELETE|\\*) /\\S*$"))) {
                    "endpoint는 'METHOD /path' 형식이어야 한다: ${entry.endpoint}"
                }
                val roots = if (entry.path.startsWith("{")) entry.path.trim('{', '}').split(',') else listOf(entry.path)
                require(roots.all { it in setOf("status", "**", "$") || it.startsWith("header:") || it.startsWith("$") }) {
                    "path는 status, header:<이름>, \$…, **, 또는 그 {a,b} 묶음이어야 한다: ${entry.path}"
                }
                entry.kind?.let { DiffKind.of(it) }
                require(entry.label == null || entry.scenario != null) { "label에는 scenario가 필요하다: $entry" }
                require(entry.element == null || entry.kind in setOf(DiffKind.ONLY_V1.code, DiffKind.ONLY_V2.code)) {
                    "element는 kind: only-v1/only-v2에만 쓴다: $entry"
                }
                require((entry.kind == DiffKind.ORDER.code) == !entry.sortBy.isNullOrEmpty()) {
                    "kind: order에는 sortBy가 필요하고, sortBy는 kind: order에만 쓴다: $entry"
                }
            }
            return AllowedDiffs(entries)
        }
    }
}
