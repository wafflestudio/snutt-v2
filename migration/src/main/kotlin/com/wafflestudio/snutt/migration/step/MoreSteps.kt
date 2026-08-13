package com.wafflestudio.snutt.migration.step

import com.mongodb.client.MongoClient
import com.wafflestudio.snutt.migration.IdMaps
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

// course: 구 ev lecture(course_number+instructor 단위) → 신 course (id 재채번)
@Component
class CourseStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
    private val oldEvJdbc: JdbcTemplate,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "course"

    override fun run() {
        var count = 0
        oldEvJdbc.query(
            "SELECT id, course_number, instructor, title, department, credit, academic_year, category, classification, created_at, updated_at FROM lecture",
        ) { rs ->
            insert(
                "course",
                listOf(
                    "course_number",
                    "instructor",
                    "title",
                    "department",
                    "credit",
                    "academic_year",
                    "category",
                    "classification",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    rs.getString("course_number") ?: "",
                    rs.getString("instructor") ?: "",
                    rs.getString("title") ?: "",
                    rs.getString("department"),
                    rs.getObject("credit") as? Number?,
                    rs.getString("academic_year"),
                    rs.getString("category"),
                    rs.getString("classification"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at"),
                ),
            )
            idMaps.put("evLecture", rs.getLong("id").toString(), lastInsertId())
            count++
        }
        log.info("course 이관: {}건", count)
    }
}

// tag: 구 ev tag_group/tag → 신 tag_group/tag
@Component
class TagStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
    private val oldEvJdbc: JdbcTemplate,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "tag"

    override fun run() {
        var groupCount = 0
        oldEvJdbc.query("SELECT id, name, ordering, color, value_type, created_at, updated_at FROM tag_group") { rs ->
            insert(
                "tag_group",
                listOf("name", "ordering", "color", "value_type", "created_at", "updated_at"),
                listOf(
                    rs.getString("name"),
                    rs.getInt("ordering"),
                    rs.getString("color"),
                    rs.getString("value_type"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at"),
                ),
            )
            idMaps.put("tagGroup", rs.getLong("id").toString(), lastInsertId())
            groupCount++
        }
        var tagCount = 0
        oldEvJdbc.query(
            "SELECT id, tag_group_id, name, description, ordering, int_value, string_value, created_at, updated_at FROM tag",
        ) { rs ->
            val newGroupId = idMaps.get("tagGroup", rs.getLong("tag_group_id").toString()) ?: return@query
            insert(
                "tag",
                listOf("tag_group_id", "name", "description", "ordering", "int_value", "string_value", "created_at", "updated_at"),
                listOf(
                    newGroupId,
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getInt("ordering"),
                    rs.getObject("int_value") as? Number?,
                    rs.getString("string_value"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at"),
                ),
            )
            tagCount++
        }
        log.info("tag_group {}건, tag {}건 이관", groupCount, tagCount)
    }
}

// timetable: 시간표 + 항목(lecture 참조 + override 컬럼, PLAN.md §2)
@Component
class TimetableStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "timetable"

    override fun run() {
        var count = 0
        collection("timetables").find().forEach { doc ->
            val userId = idMaps.get("user", doc.string("user_id") ?: "") ?: return@forEach
            val themeId = doc.string("theme_id")?.let { idMaps.get("theme", it) }
            insert(
                "timetable",
                listOf(
                    "external_id",
                    "user_id",
                    "year",
                    "semester",
                    "title",
                    "theme",
                    "theme_id",
                    "is_primary",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    doc.externalId(),
                    userId,
                    doc.year(),
                    doc.semester(),
                    doc.string("title") ?: "",
                    (doc.get("theme") as? Number)?.toInt() ?: 0,
                    themeId,
                    doc.bool("is_primary"),
                    Timestamp.from(doc.instant("created_at") ?: now()),
                    Timestamp.from(doc.instant("updated_at") ?: now()),
                ),
            )
            val newTimetableId = lastInsertId()
            idMaps.put("timetable", doc.externalId(), newTimetableId)

            (doc.get("lecture_list") as? List<*>)?.forEach { lectureDoc ->
                val tl = lectureDoc as Document
                val lectureId = tl.string("lecture_id")?.let { idMaps.get("lecture", it) }
                val colorJson = tl.get("color")?.toString()
                // lecture 참조면 차이분만, custom이면 내용 전체를 override 컬럼에 기록한다
                val override = buildOverride(tl, lectureId)
                insert(
                    "timetable_lecture",
                    listOf(
                        "external_id",
                        "timetable_id",
                        "lecture_id",
                        "color",
                        "color_index",
                        "course_title",
                        "instructor",
                        "credit",
                        "remark",
                        "class_place_and_time",
                        "academic_year",
                        "category",
                        "classification",
                        "category_pre2025",
                        "created_at",
                        "updated_at",
                    ),
                    listOf(
                        tl.externalId(),
                        newTimetableId,
                        lectureId,
                        colorJson,
                        tl.int("color_index") ?: 0,
                        override["course_title"],
                        override["instructor"],
                        (override["credit"] as? Number)?.toInt(),
                        override["remark"],
                        override["class_place_and_time"],
                        override["academic_year"],
                        override["category"],
                        override["classification"],
                        override["category_pre2025"],
                        Timestamp.from(tl.instant("created_at") ?: now()),
                        Timestamp.from(tl.instant("updated_at") ?: now()),
                    ),
                )
            }
            count++
        }
        log.info("timetable 이관: {}건", count)
    }

    // lecture_id NULL(custom)이면 doc 내용을, 아니면 스냅샷과 현재 lecture 행의 차이만 override로 돌려준다
    private fun buildOverride(
        tl: Document,
        lectureId: Long?,
    ): Map<String, Any?> {
        val fields =
            listOf(
                "course_title" to "course_title",
                "instructor" to "instructor",
                "credit" to "credit",
                "remark" to "remark",
                "class_place_and_time" to "class_time_json",
                "academic_year" to "academic_year",
                "category" to "category",
                "classification" to "classification",
                "category_pre2025" to "categoryPre2025",
            )
        if (lectureId == null) {
            return fields.associate { (column, docKey) ->
                column to
                    (if (docKey == "class_time_json") tl.get(docKey)?.toString() else tl.get(docKey))
            }
        }
        val current =
            jdbc
                .query(
                    "SELECT course_title, instructor, credit, remark, class_place_and_time, academic_year, category, classification, category_pre2025 FROM lecture WHERE id = ?",
                    org.springframework.jdbc.core.RowMapper { rs, _ ->
                        fields.associate { (column, _) -> column to rs.getObject(column) }
                    },
                    lectureId,
                ).firstOrNull() ?: return emptyMap()
        return fields
            .mapNotNull { (column, docKey) ->
                val docValue = if (docKey == "class_time_json") tl.get(docKey)?.toString() else tl.get(docKey)
                if (docValue != current[column]) column to docValue else null
            }.toMap()
    }
}

// bookmark: 스냅샷 폐기, lecture FK 참조로 전환
@Component
class BookmarkStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "bookmark"

    override fun run() {
        var count = 0
        collection("bookmarks").find().forEach { doc ->
            val userId = idMaps.get("user", doc.string("user_id") ?: "") ?: return@forEach
            insert(
                "bookmark",
                listOf("external_id", "user_id", "year", "semester", "created_at", "updated_at"),
                listOf(doc.externalId(), userId, doc.year(), doc.semester(), now(), now()),
            )
            val bookmarkId = lastInsertId()
            var lectureCount = 0
            (doc.get("lectures") as? List<*>)?.forEach { lectureDoc ->
                val lectureId = idMaps.get("lecture", (lectureDoc as Document).externalId()) ?: return@forEach
                jdbc.update(
                    "INSERT INTO bookmark_lecture (bookmark_id, lecture_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    bookmarkId,
                    lectureId,
                    now(),
                    now(),
                )
                lectureCount++
            }
            count++
        }
        log.info("bookmark 이관: {}건", count)
    }
}

// theme/friend/misc: 롱테일 테이블 (구 Mongo → 신 MySQL, id 재채번)
@Component
class ThemeStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "theme"

    override fun run() {
        var count = 0
        var publishedCount = 0
        collection("themes").find().forEach { doc ->
            val userId = idMaps.get("user", doc.string("user_id") ?: "") ?: return@forEach
            val origin = doc.get("origin") as? Document
            val publishInfo = doc.get("publishInfo") as? Document
            val status = doc.string("status") ?: "PRIVATE"
            // builtin 테마는 이전에도 행이 없었다 (DB 행 없이 서비스 합성)
            insert(
                "theme",
                listOf("external_id", "user_id", "name", "color_list", "origin_theme_id", "origin_author_id", "created_at", "updated_at"),
                listOf(
                    doc.externalId(),
                    userId,
                    doc.string("name") ?: "",
                    doc.get("colors")?.toString() ?: "null",
                    origin?.string("originId")?.let { idMaps.get("theme", it) },
                    origin?.string("authorId")?.let { idMaps.get("user", it) },
                    Timestamp.from(doc.instant("createdAt") ?: now()),
                    Timestamp.from(doc.instant("updatedAt") ?: now()),
                ),
            )
            val themeId = lastInsertId()
            idMaps.put("theme", doc.externalId(), themeId)
            // 공개 정보는 별도 행으로 분리 (publish_name이 있는 행만 공개됐던 것)
            if (publishInfo?.string("publishName") != null) {
                insert(
                    "published_theme",
                    listOf("theme_id", "publish_name", "author_anonymous", "download_count", "created_at"),
                    listOf(
                        themeId,
                        publishInfo.string("publishName"),
                        publishInfo.get("authorAnonymous") as? Boolean ?: false,
                        publishInfo.get("downloads") as? Number ?: 0,
                        Timestamp.from(doc.instant("updatedAt") ?: now()),
                    ),
                )
                publishedCount++
            }
            count++
        }
        log.info("theme 이관: {}건 (공개 {}건)", count, publishedCount)
    }
}

@Component
class FriendStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "friend"

    override fun run() {
        var count = 0
        collection("friends").find().forEach { doc ->
            val fromUserId = idMaps.get("user", doc.string("fromUserId") ?: "") ?: return@forEach
            val toUserId = idMaps.get("user", doc.string("toUserId") ?: "") ?: return@forEach
            insert(
                "friend",
                listOf(
                    "external_id",
                    "from_user_id",
                    "to_user_id",
                    "from_display_name",
                    "to_display_name",
                    "is_accepted",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    doc.externalId(),
                    fromUserId,
                    toUserId,
                    doc.string("fromDisplayName"),
                    doc.string("toDisplayName"),
                    doc.bool("isAccepted"),
                    now(),
                    now(),
                ),
            )
            count++
        }
        log.info("friend 이관: {}건", count)
    }
}

@Component
class MiscStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "misc"

    override fun run() {
        var notificationCount = 0
        collection("notifications").find().forEach { doc ->
            insert(
                "notification",
                listOf("external_id", "user_id", "title", "message", "type", "deeplink", "created_at", "updated_at"),
                listOf(
                    doc.externalId(),
                    doc.string("user_id")?.let { idMaps.get("user", it) },
                    doc.string("title") ?: "",
                    doc.string("message") ?: "",
                    (doc.get("type") as? Number)?.toInt() ?: 0,
                    doc.string("deeplink"),
                    Timestamp.from(doc.instant("created_at") ?: now()),
                    Timestamp.from(doc.instant("updated_at") ?: now()),
                ),
            )
            notificationCount++
        }
        var vacancyCount = 0
        collection("vacancy_notifications").find().forEach { doc ->
            val userId = idMaps.get("user", doc.string("user_id") ?: "") ?: return@forEach
            val lectureId = idMaps.get("lecture", doc.string("lecture_id") ?: "") ?: return@forEach
            insert(
                "vacancy_notification",
                listOf("external_id", "user_id", "lecture_id", "created_at", "updated_at"),
                listOf(doc.externalId(), userId, lectureId, now(), now()),
            )
            vacancyCount++
        }
        var deviceCount = 0
        collection("user_devices").find().forEach { doc ->
            val userId = idMaps.get("user", doc.string("user_id") ?: "") ?: return@forEach
            insert(
                "user_device",
                listOf(
                    "external_id",
                    "user_id",
                    "os_type",
                    "os_version",
                    "device_id",
                    "device_model",
                    "app_type",
                    "app_version",
                    "fcm_registration_id",
                    "is_deleted",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    doc.externalId(),
                    userId,
                    doc.string("os_type"),
                    doc.string("os_version"),
                    doc.string("device_id"),
                    doc.string("device_model"),
                    doc.string("app_type"),
                    doc.string("app_version"),
                    doc.string("fcm_registration_id") ?: "",
                    doc.bool("is_deleted"),
                    now(),
                    now(),
                ),
            )
            deviceCount++
        }
        log.info("notification {}건, vacancy {}건, device {}건 이관", notificationCount, vacancyCount, deviceCount)
    }
}

// evaluation: id 재채번, semester_lecture_id → course_id + year + semester, user_id 문자열 → FK
@Component
class EvaluationStep(
    mongoClient: MongoClient,
    jdbc: JdbcTemplate,
    idMaps: IdMaps,
    private val oldEvJdbc: JdbcTemplate,
) : AbstractMigrationStep(mongoClient, jdbc, idMaps) {
    override val name = "evaluation"

    override fun run() {
        // semester_lecture → (course, year, semester)
        val anchorBySemesterLectureId = mutableMapOf<Long, Triple<Long, Int, Int>>()
        oldEvJdbc.query(
            "SELECT sl.id, sl.year, sl.semester, l.course_number, l.instructor FROM semester_lecture sl JOIN lecture l ON l.id = sl.lecture_id",
        ) { rs ->
            val courseId = idMaps.get("evLecture", rs.getLong("l.id").toString())
            if (courseId != null) {
                anchorBySemesterLectureId[rs.getLong("sl.id")] = Triple(courseId, rs.getInt("year"), rs.getInt("semester"))
            }
        }
        var count = 0
        oldEvJdbc.query(
            "SELECT id, semester_lecture_id, user_id, content, grade_satisfaction, teaching_skill, gains, life_balance, rating, like_count, is_hidden, is_reported, from_snuev, created_at, updated_at FROM lecture_evaluation",
        ) { rs ->
            val anchor = anchorBySemesterLectureId[rs.getLong("semester_lecture_id")] ?: return@query
            val userId = rs.getString("user_id")?.let { idMaps.get("user", it) }
            insert(
                "evaluation",
                listOf(
                    "course_id",
                    "user_id",
                    "year",
                    "semester",
                    "content",
                    "grade_satisfaction",
                    "teaching_skill",
                    "gains",
                    "life_balance",
                    "rating",
                    "like_count",
                    "is_hidden",
                    "is_reported",
                    "from_snuev",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    anchor.first,
                    userId,
                    anchor.second,
                    anchor.third,
                    rs.getString("content") ?: "",
                    rs.getObject("grade_satisfaction") as? Number,
                    rs.getObject("teaching_skill") as? Number,
                    rs.getObject("gains") as? Number,
                    rs.getObject("life_balance") as? Number,
                    rs.getDouble("rating"),
                    rs.getLong("like_count"),
                    rs.getBoolean("is_hidden"),
                    rs.getBoolean("is_reported"),
                    rs.getBoolean("from_snuev"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at"),
                ),
            )
            val newEvaluationId = lastInsertId()
            idMaps.put("evaluation", rs.getLong("id").toString(), newEvaluationId)
            count++
        }
        log.info("evaluation 이관: {}건", count)
    }
}
