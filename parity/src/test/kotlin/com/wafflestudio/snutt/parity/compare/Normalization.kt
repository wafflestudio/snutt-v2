package com.wafflestudio.snutt.parity.compare

import com.wafflestudio.snutt.parity.scenario.Side

/**
 * 응답 정규화 규칙. 비교 전에 양쪽 응답에 똑같이 적용하는 변환은 모두 여기 있다.
 * 규칙을 더할 때는 "왜 이 차이가 클라이언트에 의미가 없는지"를 주석으로 단다. 의미가 있을 수 있는 차이는 정규화하지 말고
 * allowed-diffs.yml에 엔드포인트·경로·이유와 함께 등록한다(조용히 무시하는 정규화 금지).
 */
object NormalizationRules {
    /**
     * id를 담는 키. v1 id는 Mongo ObjectId(24자 hex), v2 id는 Long이라 값이 다를 수밖에 없다.
     * 값은 [IdNormalizer]가 별칭(`@user:alice`)이나 등장 순서 번호(`#1`)로 바꾼다. JSON 타입(문자열/숫자)은 보존해
     * "id가 숫자로 나간다" 같은 차이는 그대로 잡힌다.
     */
    val ID_KEYS =
        setOf(
            "_id",
            "id",
            "user_id",
            "userId",
            "lecture_id",
            "lectureId",
            "timetable_id",
            "timetableId",
            "timetableLectureId",
            "themeId",
            "originId",
            "authorId",
            "fromUserId",
            "toUserId",
            "friendId",
            "evLectureId",
            "ev_lecture_id",
            "evaluation_id",
            "semester_lecture_id",
            "snutt_id",
            "snuttId",
            "lectureEvaluationId",
        )

    /**
     * 서버가 요청마다 새로 발급하는 불투명 토큰. 두 서버가 같은 값을 낼 수 없고, 클라이언트는 저장했다가 그대로 돌려보낼 뿐
     * 내용을 해석하지 않는다(Android `SNUTTStorage.accessToken`, iOS Keychain). 시나리오는 [ParityScenario.capture]로
     * 양쪽 값을 따로 잡아 다음 요청에 쓴다.
     */
    val TOKEN_KEYS = setOf("token")

    /**
     * 시각 문자열. 이 시나리오를 준비한 뒤(시드를 넣고 이관한 뒤) 생긴 시각은 형식만 비교한다: 요청 시각(두 서버에 순서대로
     * 보내므로 다름)이나 이관 시각(이관이 Instant.now()로 채운 값)이라 값이 같을 수 없다. 그 전의 시각(시드 값을 이관이 옮긴 것)은
     * 형식과 값을 함께 비교해 시간대·날짜 밀림 같은 이관 오류를 잡는다.
     * 형식은 "날짜만/오프셋 없음/Z/오프셋" 네 가지이고, 값은 같은 순간이면 같게 쓴다(소수부 자릿수는 보지 않음): v1은 Mongo 밀리초,
     * v2는 MySQL 마이크로초를 저장하고, 값이 0이면 Java가 소수부를 생략한다. 클라이언트는 소수부 자릿수와 무관하게 파싱한다
     * (Android `Instant.parse`는 0~9자리, iOS는 ISO8601 ± 소수부 두 형식을 시도. docs/v1-compat-audit.md "JSON 직렬화").
     * 오프셋 없는 시각과 날짜는 UTC로 보고 판정한다.
     */
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val DATETIME = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})?$""")

    fun datetimeShape(value: String): String? {
        if (DATE.matches(value)) return "<date>"
        val match = DATETIME.matchEntire(value) ?: return null
        return when (val zone = match.groupValues[3]) {
            "" -> "<datetime:local>"
            "Z" -> "<datetime:Z>"
            else -> "<datetime:$zone>"
        }
    }

    /** [volatileSince] 이후의 시각이면 형식만, 아니면 형식과 정규화한 값을 돌려준다. 시각이 아니면 null. */
    fun datetimeToken(
        value: String,
        volatileSince: java.time.Instant,
    ): String? {
        val shape = datetimeShape(value) ?: return null
        val instant =
            when (shape) {
                "<date>" ->
                    java.time.LocalDate
                        .parse(value)
                        .atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant()
                "<datetime:local>" ->
                    java.time.LocalDateTime
                        .parse(value)
                        .toInstant(java.time.ZoneOffset.UTC)
                else ->
                    java.time.OffsetDateTime
                        .parse(value)
                        .toInstant()
            }
        if (!instant.isBefore(volatileSince)) return shape
        return if (shape == "<date>") "$shape$value" else "$shape$instant"
    }

    /** v1 ObjectId. 이 모양이 아닌 값(EV의 숫자 id 등)은 v1 쪽에서 등장 순서 번호로 바꾸지 않는다. */
    val OBJECT_ID = Regex("^[0-9a-f]{24}$")
    val NUMERIC = Regex("^[0-9]+$")
}

/**
 * id 값을 양쪽 공통 표기로 바꾼다.
 * 1. 시드·캡처로 아는 값은 별칭(`@user:alice`). 양쪽에서 같은 별칭이 나오면 같은 대상을 가리킨다.
 *    v2 id는 테이블마다 1부터 시작해 서로 겹치므로, v2 값은 같은 경로에 있는 v1 값의 별칭 종류(`user`, `tt` …)를 힌트로 고른다.
 *    힌트가 없으면 후보가 하나일 때만 별칭으로 바꾼다.
 * 2. 모르는 값은 그 시나리오 안에서 같은 자리(엔드포인트 + 경로)에 처음 나온 순서대로 `#1`, `#2`
 *    (`_id`·`id` 외에는 `#lectureid:1`처럼 키를 붙임). 시나리오 중에 새로 생긴 id나, 시드 별칭이 없는 외부 덤프의 id용이다.
 *    v1은 ObjectId 모양일 때만, v2는 숫자이고 SNUTT 엔드포인트일 때만 번호를 매긴다. EV 엔드포인트는 양쪽 다 숫자 id라
 *    번호를 매기면 서로 다른 값(예: 태그 그룹 PK와 ordering)이 같아 보이므로 원래 값을 그대로 비교한다.
 * 숫자로 나간 id에는 `~num`을 붙여 문자열 id와 구별한다.
 */
class IdNormalizer(
    known: Map<Side, Map<String, List<String>>>,
    /** 이 시각 이후의 시각 값은 형식만 비교한다([NormalizationRules.datetimeToken]). */
    val volatileSince: java.time.Instant = java.time.Instant.EPOCH,
) {
    private val reverse = known.mapValues { (_, table) -> HashMap(table.mapValues { it.value.toMutableList() }) }

    /**
     * 쪽·자리별 순번. 자리는 "엔드포인트 + 인덱스를 뺀 경로"(`GET /v1/tables/{id} $.lecture_list[*]._id`)다. 같은 자리에는 같은 종류의
     * 엔티티만 오므로, 테이블마다 1부터 시작하는 v2 id가 다른 종류의 id와 같은 번호를 받지 않는다.
     */
    private val ordinals = HashMap<Pair<Side, String>, LinkedHashMap<String, Int>>()

    /** 시나리오 중에 캡처한 별칭. 새로 생긴 값이라 유일하다. */
    private val captured = HashSet<String>()

    /** 지금 비교 중인 응답에서 v1 쪽이 자리마다 쓴 별칭. 순서가 다른 목록에서 v2 값을 같은 별칭으로 맞출 때만 쓴다. */
    private val v1SlotAliases = HashMap<String, MutableSet<String>>()

    fun beginExchange() = v1SlotAliases.clear()

    /** 자리마다 지금까지 나온 별칭 종류(`tt`, `user` …). 같은 자리에는 같은 종류만 온다. */
    private val slotKinds = HashMap<String, MutableSet<String>>()

    /**
     * deeplink 안의 `timetableId=`·`lectureId=` 값은 이관이 새 id로 바꾼다(NotificationStep.rewriteDeeplink). 그 값을 id처럼
     * 별칭으로 바꾸고 나머지 문자열은 그대로 비교한다.
     */
    fun normalizeDeeplink(
        side: Side,
        slot: String,
        value: String,
        evEndpoint: Boolean,
    ): String =
        DEEPLINK_ID.replace(value) { match ->
            val (name, id) = match.destructured
            // 종류는 파라미터 이름으로 정해진다(timetableId → 시간표, lectureId → 수강편람 강의).
            val kindHint = if (name == "timetableId") "@tt:" else "@lecture:"
            "$name=" + normalize(side, name, "$slot?$name", id, evEndpoint, kindHint)
        }

    fun register(
        side: Side,
        value: String,
        alias: String,
    ) {
        captured += alias
        reverse.getValue(side).getOrPut(value) { mutableListOf() }.add(0, alias)
    }

    fun normalize(
        side: Side,
        key: String,
        slot: String,
        value: Any,
        evEndpoint: Boolean,
        hint: Any?,
    ): Any {
        val raw = value.toString()
        val suffix = if (value is Number) NUMBER_SUFFIX else ""
        val hintAlias = (hint as? String)?.takeIf { it.startsWith("@") }?.removePrefix("@")?.removeSuffix(NUMBER_SUFFIX)
        val candidates = reverse.getValue(side)[raw].orEmpty()
        // EV 엔드포인트의 v2 숫자 id(태그·과목·강의평)는 SNUTT 테이블 id와 범위가 겹친다. 같은 경로의 v1 별칭이 없으면
        // EV 쪽 별칭(과목·강의평·학기 강의) 중 하나로 정해질 때만 바꾸고, 아니면 원래 값을 둔다.
        if (side == Side.V2 && evEndpoint && hintAlias == null) {
            val evCandidates = reverse.getValue(side)[raw].orEmpty().filter { kind(it) in EV_KINDS }
            return if (evCandidates.size == 1) "@${evCandidates.single()}$suffix" else value
        }
        val chosen =
            when {
                candidates.isEmpty() -> null
                side == Side.V1 -> candidates.first()
                hintAlias != null && hintAlias in candidates -> hintAlias
                hintAlias != null -> candidates.firstOrNull { kind(it) == kind(hintAlias) }
                // v1 쪽이 새 id(#n)면 v2 쪽도 새 id로 본다. 우연히 같은 숫자의 시드 id로 바꾸지 않는다. 단 같은 응답의 같은 자리에서 v1이
                // 그 캡처 별칭을 썼다면(순서만 다른 목록) 그 별칭을 쓴다.
                (hint as? String)?.startsWith("#") == true ->
                    candidates.firstOrNull { it in captured && it in v1SlotAliases[slot].orEmpty() }
                candidates.size == 1 -> candidates.single()
                else -> null
            }
        if (chosen != null) {
            if (side == Side.V1) v1SlotAliases.getOrPut(slot) { HashSet() } += chosen
            slotKinds.getOrPut(slot) { HashSet() } += kind(chosen)
            return "@$chosen$suffix"
        }
        // v1은 새 id인데 v2 값이 이 자리에 오던 종류의 기존(시드) 엔티티다. 예: 복사 응답에 원본이 그대로 나옴. 순번을 주면
        // v1의 새 id와 같아 보이므로 후보를 그대로 적어 차이로 남긴다.
        if (side == Side.V2 && (hint as? String)?.startsWith("#") == true) {
            val sameKind = candidates.filter { it !in captured && kind(it) in slotKinds[slot].orEmpty() }
            if (sameKind.isNotEmpty()) return "@{${sameKind.sorted().joinToString("|")}}$suffix"
        }
        // 이관이 id를 보존하는 값(EV 과목·강의평 id)은 같은 자리의 v1 원래 값과 같다. 별칭이 없어도 그대로 둔다.
        if (side == Side.V2 && hint != null && hint !is String && hint.toString() == raw) return value
        // 아는 값인데 어느 별칭인지 정할 수 없다(v2 id는 테이블마다 겹침). 순번을 매기면 이후 번호가 밀리므로 후보를 그대로 적는다.
        if (side == Side.V2 && candidates.isNotEmpty() && hint == null) return "@{${candidates.sorted().joinToString("|")}}$suffix"
        val assignable =
            when (side) {
                Side.V1 -> NormalizationRules.OBJECT_ID.matches(raw)
                Side.V2 -> !evEndpoint && NormalizationRules.NUMERIC.matches(raw)
            }
        if (!assignable) return value
        val table = ordinals.getOrPut(side to slot) { LinkedHashMap() }
        val ordinal = table.getOrPut(raw) { table.size + 1 }
        return "#${ordinalGroup(key)}$ordinal$suffix"
    }

    private fun kind(alias: String) = alias.substringBefore(':')

    /** 같은 대상을 가리키는 키는 한 묶음으로 센다(`lecture_id`와 `lectureId`). */
    private fun ordinalGroup(key: String): String =
        key.removePrefix("_").replace("_", "").lowercase().let {
            if (it ==
                "id"
            ) {
                ""
            } else {
                "$it:"
            }
        }

    companion object {
        const val NUMBER_SUFFIX = "~num"
        private val EV_KINDS = setOf("course", "course-minted", "evaluation", "semester")
        private val DEEPLINK_ID = Regex("""(timetableId|lectureId)=([0-9A-Za-z]+)""")
    }
}

object Normalizer {
    /**
     * [body]는 JSON을 Map/List/원시값으로 읽은 트리다. 새 트리를 돌려준다.
     * [hint]는 v2를 정규화할 때 넘기는 v1의 정규화 결과로, 같은 경로의 id 별칭 종류를 알려 준다.
     */
    fun normalize(
        side: Side,
        body: Any?,
        ids: IdNormalizer,
        evEndpoint: Boolean,
        endpoint: String,
        hint: Any? = null,
    ): Any? = Walk(side, ids, evEndpoint, endpoint).walk(null, "$", body, hint)

    private class Walk(
        val side: Side,
        val ids: IdNormalizer,
        val evEndpoint: Boolean,
        val endpoint: String,
    ) {
        fun walk(
            key: String?,
            pattern: String,
            node: Any?,
            hint: Any?,
        ): Any? =
            when (node) {
                is Map<*, *> ->
                    node.entries.associate { (k, v) ->
                        k.toString() to walk(k.toString(), "$pattern.$k", v, (hint as? Map<*, *>)?.get(k.toString()))
                    }
                is List<*> -> node.mapIndexed { i, v -> walk(key, "$pattern[*]", v, (hint as? List<*>)?.getOrNull(i)) }
                null -> null
                else -> leaf(key, pattern, node, hint)
            }

        private fun leaf(
            key: String?,
            pattern: String,
            value: Any,
            hint: Any?,
        ): Any = Normalizer.leaf(side, key, "$endpoint $pattern", value, ids, evEndpoint, hint)
    }

    private fun leaf(
        side: Side,
        key: String?,
        slot: String,
        value: Any,
        ids: IdNormalizer,
        evEndpoint: Boolean,
        hint: Any?,
    ): Any {
        if (key in NormalizationRules.TOKEN_KEYS && value is String && value.isNotBlank()) return "<token>"
        if (key != null && key in NormalizationRules.ID_KEYS && (value is String || value is Number)) {
            return ids.normalize(side, key, slot, value, evEndpoint, hint)
        }
        if (key == "deeplink" && value is String) return ids.normalizeDeeplink(side, slot, value, evEndpoint)
        if (value is String) NormalizationRules.datetimeToken(value, ids.volatileSince)?.let { return it }
        return value
    }
}
