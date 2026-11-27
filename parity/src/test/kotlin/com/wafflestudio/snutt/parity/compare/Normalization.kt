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
     * 시각 문자열은 형식만 비교한다. 값은 요청 시각(두 서버에 순서대로 보내므로 다름)이나 이관 시각에 따라 달라진다.
     * 형식은 "날짜만/오프셋 없음/Z/오프셋" 네 가지로 나누고, 소수부 자릿수는 보지 않는다: v1은 Mongo 밀리초, v2는 MySQL
     * 마이크로초를 저장하고, 값이 0이면 Java가 소수부를 생략한다. 클라이언트는 소수부 자릿수와 무관하게 파싱한다
     * (Android `Instant.parse`는 0~9자리, iOS는 ISO8601 ± 소수부 두 형식을 시도. docs/v1-compat-audit.md "JSON 직렬화").
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

    /** v1 ObjectId. 이 모양이 아닌 값(EV의 숫자 id 등)은 v1 쪽에서 등장 순서 번호로 바꾸지 않는다. */
    val OBJECT_ID = Regex("^[0-9a-f]{24}$")
    val NUMERIC = Regex("^[0-9]+$")
}

/**
 * id 값을 양쪽 공통 표기로 바꾼다.
 * 1. 시드·캡처로 아는 값은 별칭(`@user:alice`). 양쪽에서 같은 별칭이 나오면 같은 대상을 가리킨다.
 *    v2 id는 테이블마다 1부터 시작해 서로 겹치므로, v2 값은 같은 경로에 있는 v1 값의 별칭 종류(`user`, `tt` …)를 힌트로 고른다.
 *    힌트가 없으면 후보가 하나일 때만 별칭으로 바꾼다.
 * 2. 모르는 값은 그 시나리오 안에서 키별로 처음 나온 순서대로 `#1`, `#2`(`_id`·`id` 외에는 `#lectureid:1`처럼 키를 붙임).
 *    시나리오 중에 새로 생긴 id용이다.
 *    v1은 ObjectId 모양일 때만, v2는 숫자이고 SNUTT 엔드포인트일 때만 번호를 매긴다. EV 엔드포인트는 양쪽 다 숫자 id라
 *    번호를 매기면 서로 다른 값(예: 태그 그룹 PK와 ordering)이 같아 보이므로 원래 값을 그대로 비교한다.
 * 숫자로 나간 id에는 `~num`을 붙여 문자열 id와 구별한다.
 */
class IdNormalizer(
    known: Map<Side, Map<String, List<String>>>,
) {
    private val reverse = known.mapValues { (_, table) -> HashMap(table.mapValues { it.value.toMutableList() }) }

    /** 쪽·키별 순번. 한쪽에만 있는 id(예: v2에만 있는 snuttEvLecture.evLectureId)가 다른 키의 번호를 밀지 않게 키마다 따로 센다. */
    private val ordinals = HashMap<Pair<Side, String>, LinkedHashMap<String, Int>>()

    /** 시나리오 중에 캡처한 별칭. 새로 생긴 값이라 유일하다. */
    private val captured = HashSet<String>()

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
                // v1 쪽이 새 id(#n)면 v2 쪽도 새 id로 본다. 우연히 같은 숫자의 시드 id로 바꾸지 않되, 캡처한 별칭은 유일하므로 쓴다.
                (hint as? String)?.startsWith("#") == true -> candidates.firstOrNull { it in captured }
                candidates.size == 1 -> candidates.single()
                else -> null
            }
        if (chosen != null) return "@$chosen$suffix"
        // 아는 값인데 어느 별칭인지 정할 수 없다(v2 id는 테이블마다 겹침). 순번을 매기면 이후 번호가 밀리므로 후보를 그대로 적는다.
        if (side == Side.V2 && candidates.isNotEmpty() && hint == null) return "@{${candidates.sorted().joinToString("|")}}$suffix"
        val assignable =
            when (side) {
                Side.V1 -> NormalizationRules.OBJECT_ID.matches(raw)
                Side.V2 -> !evEndpoint && NormalizationRules.NUMERIC.matches(raw)
            }
        if (!assignable) return value
        val table = ordinals.getOrPut(side to ordinalGroup(key)) { LinkedHashMap() }
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
        hint: Any? = null,
    ): Any? = walk(side, null, body, ids, evEndpoint, hint)

    private fun walk(
        side: Side,
        key: String?,
        node: Any?,
        ids: IdNormalizer,
        evEndpoint: Boolean,
        hint: Any?,
    ): Any? =
        when (node) {
            is Map<*, *> ->
                node.entries.associate { (k, v) ->
                    k.toString() to walk(side, k.toString(), v, ids, evEndpoint, (hint as? Map<*, *>)?.get(k.toString()))
                }
            is List<*> -> node.mapIndexed { i, v -> walk(side, key, v, ids, evEndpoint, (hint as? List<*>)?.getOrNull(i)) }
            null -> null
            else -> leaf(side, key, node, ids, evEndpoint, hint)
        }

    private fun leaf(
        side: Side,
        key: String?,
        value: Any,
        ids: IdNormalizer,
        evEndpoint: Boolean,
        hint: Any?,
    ): Any {
        if (key in NormalizationRules.TOKEN_KEYS && value is String) return "<token>"
        if (key != null && key in NormalizationRules.ID_KEYS && (value is String || value is Number)) {
            return ids.normalize(side, key, value, evEndpoint, hint)
        }
        if (value is String) NormalizationRules.datetimeShape(value)?.let { return it }
        return value
    }
}
