package com.wafflestudio.snutt.migration.rehearsal.check

import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.long
import com.wafflestudio.snutt.migration.nullIfBlank
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.str
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.random.Random

/**
 * seed를 고정한 무작위 표본 사용자마다 v1 원본과 v2 결과를 필드 단위로 대조한다.
 *
 * v1 id → v2 id는 이관이 남기는 표(MigrationContext)로만 찾는다(v2에 v1 id가 남지 않는다). 값의 기대치는 원본에서 직접 계산한다.
 * 시간표 강의는 저장 형태(override)가 아니라 앱이 보는 값(override가 없으면 강의 값)을 v1 문서와 비교한다.
 */
class SampleComparison(
    private val source: SourceIndex,
    private val v2: JdbcTemplate,
    private val context: MigrationContext,
) {
    class Result(
        val users: List<String>,
        val comparedFields: Long,
        val mismatches: List<String>,
    )

    private val json = JsonMapper.builder().build()
    private val mismatches = mutableListOf<String>()
    private var compared = 0L
    private val v1UserOf: Map<Long, String> by lazy { context.userIds.entries.associate { (hex, id) -> id to hex } }

    fun run(
        size: Int,
        seed: Long,
    ): Result {
        val sample = sample(source.users.keys, size, seed)
        sample.forEach { hex ->
            val v2Id = context.userIds[hex]
            if (v2Id == null) {
                mismatches += "$hex: v2 사용자 없음"
                return@forEach
            }
            val user = source.users.getValue(hex)
            compareUser(user, v2Id)
            compareTimetables(hex, v2Id)
            compareUserData(user, v2Id)
        }
        return Result(sample, compared, mismatches)
    }

    private fun check(
        where: String,
        field: String,
        v1: Any?,
        v2: Any?,
    ) {
        compared++
        // 개수·연도처럼 Int와 Long으로 갈려 오는 숫자는 값으로 비교한다.
        val same = if (v1 is Number && v2 is Number) v1.toLong() == v2.toLong() else v1 == v2
        if (!same) mismatches += "$where.$field: v1=$v1, v2=$v2"
    }

    private fun compareUser(
        user: SourceIndex.SourceUser,
        id: Long,
    ) {
        val row = v2.queryForMap("SELECT * FROM `user` WHERE id = ?", id)
        val where = "user ${user.hex}"
        val doc =
            source.mongo
                .getCollection("users")
                .find(Filters.eq("_id", ObjectId(user.hex)))
                .first()!!
        val nickname = doc.str("nickname").orEmpty()
        check(where, "email", user.email, row["email"])
        check(where, "nickname", nickname.substringBeforeLast('#'), row["nickname"])
        check(where, "nickname_tag", nickname.substringAfterLast('#'), row["nickname_tag"])
        check(where, "active", user.active, row["active"])
        check(where, "is_admin", doc.bool("isAdmin"), row["is_admin"])
        val expectedLocalId = user.localId?.takeIf { !user.active || source.ownsLocalId(user) }
        check(where, "local_id", expectedLocalId, row["local_id"])
        val demoted = user.active && user.verified == true && user.email != null && !source.ownsEmail(user)
        check(where, "is_email_verified", if (demoted) false else user.verified, row["is_email_verified"])
        val lastLogin = doc.long("lastLoginTimestamp") ?: user.regDate?.toEpochMilli()
        check(where, "last_login_at", lastLogin, epochMillis(row["last_login_at"]))

        val socials = if (user.active) user.socials.filter { source.ownsSocial(user, it) }.toSet() else emptySet()
        val v2Socials = v2.queryForList("SELECT CONCAT(provider, '|', sub) FROM user_social_auth WHERE user_id = ?", String::class.java, id)
        check(where, "user_social_auth", socials, v2Socials.toSet())
    }

    private fun compareTimetables(
        hex: String,
        userId: Long,
    ) {
        val timetables =
            source.mongo
                .getCollection("timetables")
                .find(ref("user_id", hex))
                .sort(Sorts.ascending("_id"))
                .toList()
        check("user $hex", "timetable 수", timetables.size, v2.count("SELECT COUNT(*) FROM timetable WHERE user_id = ?", userId))
        timetables.forEach { doc ->
            val where = "timetable ${doc.id()}"
            val id = context.timetableIds[doc.id()]
            if (id == null) {
                mismatches += "$where: v2 시간표 없음"
                return@forEach
            }
            val row = v2.queryForMap("SELECT * FROM timetable WHERE id = ?", id)
            check(where, "year", doc.int("year"), row["year"])
            check(where, "semester", doc.int("semester"), (row["semester"] as Number).toInt())
            val title = doc.str("title")
            val v2Title = row["title"] as String
            check(
                where,
                "title",
                title,
                if (v2Title != title &&
                    v2Title.matches(Regex(Regex.escape(title.orEmpty()) + " \\(\\d+\\)"))
                ) {
                    title
                } else {
                    v2Title
                },
            )
            if (!doc.bool("is_primary")) check(where, "is_primary", false, row["is_primary"])
            val items = doc.docs("lecture_list")
            check(where, "강의 수", items.size, v2.count("SELECT COUNT(*) FROM timetable_lecture WHERE timetable_id = ?", id))
            items.forEach { item -> compareTimetableLecture(doc.id(), item) }
        }
    }

    private fun compareTimetableLecture(
        timetableHex: String,
        item: Document,
    ) {
        val where = "timetable $timetableHex lecture ${item.id()}"
        val id = context.timetableLectureIds[timetableHex to item.id()]
        if (id == null) {
            mismatches += "$where: v2 시간표 강의 없음"
            return
        }
        val row = v2.queryForMap("SELECT lecture_id, overrides FROM timetable_lecture WHERE id = ?", id)
        val overrides: JsonNode? = (row["overrides"] as? String)?.let(json::readTree)
        val lectureId = row["lecture_id"] as Long?
        val lecture = lectureId?.let { v2.queryForMap("SELECT * FROM lecture WHERE id = ?", it) }

        val expectedOffering = item.oid("lecture_id")?.let(source.offeringOf::get)
        val actualOffering =
            lecture?.let {
                listOf(it["year"], (it["semester"] as Number).toInt(), it["course_number"], it["lecture_number"]).joinToString("|")
            }
        check(where, "개설", expectedOffering, actualOffering)

        fun effective(
            key: String,
            column: String,
        ): String? = overrides?.get(key)?.takeUnless { it.isNull }?.asString() ?: lecture?.get(column)?.toString()
        check(where, "course_title", item.str("course_title"), effective("courseTitle", "course_title"))
        check(where, "instructor", item.str("instructor").nullIfBlank(), effective("instructor", "instructor").nullIfBlank())
        check(where, "credit", (item["credit"] as? Number)?.toLong(), effective("credit", "credit")?.toLongOrNull())

        val expectedTimes =
            item.docs("class_time_json").map {
                "${it.int("day")}/${it.int("startMinute")}/${it.int("endMinute")}/${it.str("place")}"
            }
        val actualTimes: List<String> =
            overrides?.get("classPlaceAndTimes")?.let { times ->
                (0 until times.size()).map { index ->
                    val time = times.get(index)
                    "${time.get(
                        "day",
                    ).asInt()}/${time.get("startMinute").asInt()}/${time.get("endMinute").asInt()}/${time.get("place")?.asString()}"
                }
            } ?: lectureId
                ?.let {
                    v2
                        .queryForList(
                            "SELECT CONCAT(day, '/', start_minute, '/', end_minute, '/', COALESCE(place, 'null')) FROM lecture_class_time WHERE lecture_id = ? ORDER BY id",
                            String::class.java,
                            it,
                        ).map { time -> time.orEmpty() }
                }.orEmpty()
        check(where, "수업 시간", expectedTimes.sorted(), actualTimes.sorted())
    }

    private fun compareUserData(
        user: SourceIndex.SourceUser,
        id: Long,
    ) {
        val hex = user.hex
        val where = "user $hex"
        val mongo = source.mongo

        val bookmarks =
            mongo
                .getCollection("bookmarks")
                .find(ref("user_id", hex))
                .flatMap { doc -> doc.docs("lectures").mapNotNull { source.offeringOf[it.id()] } }
                .toSet()
        check(where, "북마크 개설", bookmarks, offeringsOf("bookmark_lecture", id))

        val vacancies =
            mongo
                .getCollection("vacancy_notifications")
                .find(ref("userId", hex))
                .mapNotNull {
                    source.offeringOf[it.oid("lectureId")]
                }.toSet()
        check(where, "빈자리 알림 개설", vacancies, offeringsOf("vacancy_notification", id))

        val devices =
            mongo
                .getCollection("userDevice")
                .find(ref("userId", hex))
                .map {
                    it.str("fcmRegistrationId").orEmpty()
                }.toList()
                .sorted()
        check(
            where,
            "기기 FCM 토큰",
            devices,
            v2
                .queryForList(
                    "SELECT fcm_registration_id FROM user_device WHERE user_id = ?",
                    String::class.java,
                    id,
                ).map {
                    it.orEmpty()
                }.sorted(),
        )

        val preferences =
            mongo
                .getCollection("pushPreference")
                .find(ref("userId", hex))
                .flatMap {
                    it.docs("pushPreferences")
                }.map { "${it.str("type")}|${it.bool("isEnabled")}" }
                .toSet()
        check(
            where,
            "푸시 설정",
            preferences,
            v2
                .queryForList(
                    "SELECT CONCAT(type, '|', IF(is_enabled, 'true', 'false')) FROM push_preference WHERE user_id = ?",
                    String::class.java,
                    id,
                ).toSet(),
        )

        val partners =
            if (!user.active) {
                emptySet()
            } else {
                mongo
                    .getCollection("friend")
                    .find(Filters.or(ref("fromUserId", hex), ref("toUserId", hex)))
                    .mapNotNull { doc -> if (doc.oid("fromUserId") == hex) doc.oid("toUserId") else doc.oid("fromUserId") }
                    .filter { source.users[it]?.active == true }
                    .toSet()
            }
        val v2Partners =
            v2
                .queryForList(
                    "SELECT IF(from_user_id = ?, to_user_id, from_user_id) FROM friend WHERE from_user_id = ? OR to_user_id = ?",
                    Long::class.java,
                    id,
                    id,
                    id,
                ).map { v1UserOf[it] }
                .toSet()
        check(where, "친구", partners, v2Partners)

        val notifications = mongo.getCollection("notifications").countDocuments(ref("user_id", hex))
        check(where, "개인 알림 수", notifications, v2.count("SELECT COUNT(*) FROM notification WHERE user_id = ?", id))

        val diaries = mongo.getCollection("diarySubmission").countDocuments(ref("userId", hex))
        check(where, "강의 일기장 수", diaries, v2.count("SELECT COUNT(*) FROM diary_submission WHERE user_id = ?", id))

        val evaluations =
            source.ev.queryForList(
                "SELECT le.id FROM lecture_evaluation le JOIN semester_lecture sl ON sl.id = le.semester_lecture_id WHERE le.user_id = ?",
                Long::class.java,
                hex,
            )
        check(
            where,
            "강의평",
            evaluations.toSet(),
            v2.queryForList("SELECT id FROM evaluation WHERE user_id = ?", Long::class.java, id).toSet(),
        )
        val likes = source.ev.queryForList("SELECT id FROM evaluation_like WHERE user_id = ?", Long::class.java, hex)
        check(
            where,
            "강의평 공감",
            likes.toSet(),
            v2.queryForList("SELECT id FROM evaluation_like WHERE user_id = ?", Long::class.java, id).toSet(),
        )
    }

    private fun offeringsOf(
        table: String,
        userId: Long,
    ): Set<String> =
        v2
            .queryForList(
                "SELECT CONCAT(l.year, '|', l.semester, '|', l.course_number, '|', l.lecture_number) " +
                    "FROM `$table` x JOIN lecture l ON l.id = x.lecture_id WHERE x.user_id = ?",
                String::class.java,
                userId,
            ).filterNotNull()
            .toSet()

    /** 이관의 `oid()`처럼 ObjectId와 24자 hex 문자열 참조를 모두 찾는다(오래된 문서는 문자열로 저장되어 있을 수 있다). */
    private fun ref(
        field: String,
        hex: String,
    ) = Filters.`in`(field, ObjectId(hex), hex)

    private fun JdbcTemplate.count(
        sql: String,
        vararg args: Any,
    ): Long = queryForObject(sql, Long::class.java, *args)!!

    /** DATETIME은 UTC로 저장된다(connectionTimeZone=UTC). 드라이버가 LocalDateTime이나 Timestamp로 줄 수 있다. */
    private fun epochMillis(value: Any?): Long? =
        when (value) {
            is java.time.LocalDateTime -> value.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            is java.sql.Timestamp -> value.time
            else -> null
        }

    companion object {
        /** 정렬한 뒤 seed로 섞어 앞에서 [size]개. 같은 입력·seed면 같은 표본이다. */
        fun sample(
            ids: Collection<String>,
            size: Int,
            seed: Long,
        ): List<String> = ids.sorted().shuffled(Random(seed)).take(size)
    }
}
