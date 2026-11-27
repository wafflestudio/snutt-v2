package com.wafflestudio.snutt.parity.scenario

import com.wafflestudio.snutt.parity.compare.AllowedDiff
import com.wafflestudio.snutt.parity.compare.AllowedDiffs
import com.wafflestudio.snutt.parity.compare.Diff
import com.wafflestudio.snutt.parity.compare.DiffKind
import com.wafflestudio.snutt.parity.compare.IdNormalizer
import com.wafflestudio.snutt.parity.compare.JsonBodies
import com.wafflestudio.snutt.parity.compare.JsonDiff
import com.wafflestudio.snutt.parity.compare.Normalizer
import com.wafflestudio.snutt.parity.compare.Reorder
import com.wafflestudio.snutt.parity.env.ParityContainers
import com.wafflestudio.snutt.parity.env.ParityReports
import io.jsonwebtoken.Jwts
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.spec.SecretKeySpec

enum class Side { V1, V2 }

class SideResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

class SideRequest(
    val method: String,
    val path: String,
    val body: String?,
    val headers: Map<String, String>,
)

class DiffVerdict(
    val diff: Diff,
    val allowed: AllowedDiff?,
    /** 허용은 아니지만 알려진 버그로 등록된 차이(known-failures.yml). parityTest를 실패시키지 않는다. */
    val knownFailure: AllowedDiff?,
)

class Exchange(
    val label: String,
    val endpoint: String,
    val requests: Map<Side, SideRequest>,
    val responses: Map<Side, SideResponse>,
    val bodies: Map<Side, Any?>,
    val normalized: Map<Side, Any?>,
    val diffs: List<DiffVerdict>,
) {
    val unexpected get() = diffs.filter { it.allowed == null && it.knownFailure == null }.map { it.diff }
}

/**
 * 시나리오 한 개. 같은 요청을 v1과 v2에 같은 순서로 보내고(v1 먼저), 응답을 정규화해 비교한다.
 * 실패해도 시나리오 끝까지 진행해 차이를 모두 모은 뒤 [finish]에서 한 번에 실패시킨다.
 *
 * 요청 경로·바디의 `@{이름}`은 쪽마다 다른 값으로 바뀐다(시드 별칭, [capture]로 잡은 값).
 * `as`로 사용자 별칭을 주면 그 사용자의 토큰(`token:<별칭>`)을 `x-access-token`으로 보낸다.
 */
class ParityScenario(
    val name: String,
    private val targets: Map<Side, String>,
    private val allowed: AllowedDiffs,
    /** parityTest에서만 준다. 알려진 버그(known-failures.yml)에 걸린 차이는 실패 대신 "알려진 버그"로 기록한다. */
    private val knownFailures: AllowedDiffs?,
    knownIds: Map<Side, Map<String, List<String>>>,
    initialVars: Map<Side, Map<String, String>>,
    /** 데이터를 준비하기 시작한 시각. 이후에 생긴 시각 값은 형식만 비교한다. */
    preparedSince: java.time.Instant,
) {
    private val vars = Side.entries.associateWith { side -> HashMap(initialVars.getValue(side)) }
    private val ids = IdNormalizer(knownIds, preparedSince)
    val exchanges = mutableListOf<Exchange>()

    var defaultHeaders: Map<String, String> =
        mapOf(
            "x-access-apikey" to legacyApiKey("ios"),
            "x-os-type" to "ios",
            "x-os-version" to "18.0",
            "x-app-version" to "4.0.0",
            "x-app-type" to "release",
            "Content-Type" to "application/json",
        )

    fun get(
        path: String,
        `as`: String? = null,
        headers: Map<String, String> = emptyMap(),
        label: String? = null,
    ) = exchange("GET", path, null, `as`, headers, label)

    fun post(
        path: String,
        body: String? = null,
        `as`: String? = null,
        headers: Map<String, String> = emptyMap(),
        label: String? = null,
    ) = exchange("POST", path, body, `as`, headers, label)

    fun put(
        path: String,
        body: String? = null,
        `as`: String? = null,
        headers: Map<String, String> = emptyMap(),
        label: String? = null,
    ) = exchange("PUT", path, body, `as`, headers, label)

    fun patch(
        path: String,
        body: String? = null,
        `as`: String? = null,
        headers: Map<String, String> = emptyMap(),
        label: String? = null,
    ) = exchange("PATCH", path, body, `as`, headers, label)

    fun delete(
        path: String,
        body: String? = null,
        `as`: String? = null,
        headers: Map<String, String> = emptyMap(),
        label: String? = null,
    ) = exchange("DELETE", path, body, `as`, headers, label)

    fun exchange(
        method: String,
        pathTemplate: String,
        bodyTemplate: String?,
        actor: String?,
        extraHeaders: Map<String, String>,
        label: String?,
    ): Exchange {
        val endpoint = "$method ${pathTemplate.substringBefore('?').replace(TEMPLATE, "{id}")}"
        val requests =
            Side.entries.associateWith { side ->
                val headers = LinkedHashMap(defaultHeaders)
                actor?.let { headers["x-access-token"] = resolve(side, "@{token:$it}") }
                extraHeaders.forEach { (k, v) -> if (v.isEmpty()) headers.remove(k) else headers[k] = resolve(side, v) }
                SideRequest(method, resolve(side, pathTemplate), bodyTemplate?.let { resolve(side, it) }, headers)
            }
        val responses = Side.entries.associateWith { side -> send(targets.getValue(side), requests.getValue(side)) }
        val bodies = responses.mapValues { (_, response) -> JsonBodies.parse(response.body) }
        val ev = EV_PATH.containsMatchIn(pathTemplate)
        val exchangeLabel = label ?: endpoint
        val rules = allowed.arrayRules(name, endpoint, exchangeLabel)
        val diffs = mutableListOf<Diff>()

        // 1. 순서 규칙은 정규화 전에 원래 값(과목번호·이름 같은 내용 키)으로 정렬한다. 그래야 id 순번이 양쪽에서 같은 원소에 붙는다.
        var sorted = bodies.getValue(Side.V1) to bodies.getValue(Side.V2)
        rules.filter { it.element == null }.forEach { rule ->
            val result = Reorder.apply(rule, sorted.first, sorted.second)
            result.diff?.let { diffs += it }
            sorted = result.v1 to result.v2
        }
        ids.beginExchange()
        val v1Normalized = Normalizer.normalize(Side.V1, sorted.first, ids, ev, endpoint)
        val v2Normalized = Normalizer.normalize(Side.V2, sorted.second, ids, ev, endpoint, v1Normalized)

        // 2. 원소 규칙은 정규화한 값(별칭)으로 고른다.
        var compared = v1Normalized to v2Normalized
        rules.filter { it.element != null }.forEach { rule ->
            val (result, ruleDiffs) = Reorder.applyRule(rule, compared.first, compared.second, allowed)
            diffs += ruleDiffs
            compared = result.v1 to result.v2
        }
        val normalized = mapOf(Side.V1 to compared.first, Side.V2 to compared.second)
        val (r1, r2) = responses.getValue(Side.V1) to responses.getValue(Side.V2)
        if (r1.status != r2.status) diffs += Diff("status", DiffKind.CHANGED, r1.status, r2.status)
        COMPARED_HEADERS.forEach { (header, canonical) ->
            val h1 = r1.header(header)?.let(canonical)
            val h2 = r2.header(header)?.let(canonical)
            if (h1 != h2) diffs += Diff("header:$header", DiffKind.CHANGED, h1, h2)
        }
        diffs += JsonDiff.diff(compared.first, compared.second)

        val errorResponse = r1.status >= 400 && r2.status >= 400
        val exchange =
            Exchange(
                label = exchangeLabel,
                endpoint = endpoint,
                requests = requests,
                responses = responses,
                bodies = bodies,
                normalized = normalized,
                diffs =
                    diffs.map { diff ->
                        DiffVerdict(
                            diff,
                            allowed.find(name, endpoint, exchangeLabel, errorResponse, diff),
                            knownFailures?.find(name, endpoint, exchangeLabel, errorResponse, diff),
                        )
                    },
            )
        exchanges += exchange
        return exchange
    }

    /**
     * 응답에서 값을 잡아 `@{이름}`으로 쓸 수 있게 한다. 잡은 값은 id 별칭으로도 등록되어 이후 응답에서 `@이름`으로 보인다.
     * [pointer]는 JSON Pointer(`/token`, `/0/_id`, 음수 인덱스는 뒤에서부터 `/-1/_id`). 한쪽에서라도 값이 없으면 시나리오를 멈춘다(이후 요청이 의미 없음).
     */
    fun Exchange.capture(
        variable: String,
        pointer: String,
    ): Exchange {
        Side.entries.forEach { side ->
            val value = at(bodies[side], pointer)
            checkNotNull(value) { "[$name] $label: ${side.name} 응답에 $pointer 가 없다 (status=${responses.getValue(side).status})" }
            vars.getValue(side)[variable] = value.toString()
            if (!variable.startsWith("token:")) ids.register(side, value.toString(), variable)
        }
        return this
    }

    fun value(
        side: Side,
        variable: String,
    ): String? = vars.getValue(side)[variable]

    /** 쪽마다 다른 값을 직접 정한다. 같은 자리에 쪽마다 다른 입력을 넣어 비교할 때 쓴다(id 별칭으로는 등록하지 않는다). */
    fun define(
        variable: String,
        values: Map<Side, String>,
    ) {
        values.forEach { (side, value) -> vars.getValue(side)[variable] = value }
    }

    private fun resolve(
        side: Side,
        template: String,
    ): String =
        TEMPLATE.replace(template) { match ->
            val key = match.groupValues[1]
            vars.getValue(side)[key] ?: error("[$name] ${side.name}에 @{$key} 값이 없다")
        }

    /** 시나리오 리포트를 쓰고, 등록되지 않은 차이가 있으면 실패한다. */
    fun finish() {
        ParityReports.write(this)
        val failures = exchanges.filter { it.unexpected.isNotEmpty() }
        if (failures.isEmpty()) return
        val message =
            buildString {
                appendLine(
                    "[$name] 허용 목록에 없는 차이 ${failures.sumOf { it.unexpected.size }}건 (리포트: ${ParityReports.fileOf(this@ParityScenario)})",
                )
                failures.forEach { exchange ->
                    appendLine("  ${exchange.label}")
                    exchange.unexpected.take(MAX_LISTED).forEach { appendLine("    - ${it.describe()}") }
                    if (exchange.unexpected.size > MAX_LISTED) appendLine("    … 외 ${exchange.unexpected.size - MAX_LISTED}건")
                }
            }
        throw AssertionError(message)
    }

    companion object {
        private const val MAX_LISTED = 15
        private val TEMPLATE = Regex("""@\{([^}]+)}""")
        private val EV_PATH = Regex("""^/(v1/)?ev(-service)?/""")

        /** 클라이언트가 읽는 응답 헤더. Content-Type은 미디어 타입만 본다(charset은 양쪽 모두 UTF-8 JSON이라 클라이언트가 보지 않음). */
        private val COMPARED_HEADERS: Map<String, (String) -> String> =
            mapOf(
                "Content-Type" to { it.substringBefore(';').trim().lowercase() },
                "Location" to { it },
            )

        private val http: HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()

        fun send(
            base: String,
            request: SideRequest,
        ): SideResponse {
            val builder = HttpRequest.newBuilder(URI.create(base + request.path)).timeout(Duration.ofSeconds(30))
            request.headers.forEach { (k, v) -> builder.header(k, v) }
            val publisher = request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            builder.method(request.method, publisher)
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return SideResponse(response.statusCode(), response.headers().map(), response.body() ?: "")
        }

        private fun at(
            body: Any?,
            pointer: String,
        ): Any? {
            var node = body
            pointer.removePrefix("/").split('/').filter { it.isNotEmpty() }.forEach { token ->
                node =
                    when (val current = node) {
                        is Map<*, *> -> current[token]
                        is List<*> -> token.toIntOrNull()?.let { current.getOrNull(if (it < 0) current.size + it else it) }
                        else -> null
                    }
            }
            return node
        }
    }
}

/** v1 `ApiKeyWebFilter`와 v2 `LegacyApiKeyVerifier`가 함께 받는 구 앱 API key(JWT HS256, string + key_version). */
fun legacyApiKey(
    platform: String,
    keyVersion: String = "0",
): String =
    Jwts
        .builder()
        .claim("string", platform)
        .claim("key_version", keyVersion)
        .signWith(SecretKeySpec(ParityContainers.LEGACY_SECRET_KEY.toByteArray(), "HmacSHA256"), Jwts.SIG.HS256)
        .compact()
