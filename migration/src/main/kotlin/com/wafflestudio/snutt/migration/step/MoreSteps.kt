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

// timetable: 시간표 + 항목(lecture 참조) + customization (스냅샷 diff, PLAN.md §2)
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
                insert(
                    "timetable_lecture",
                    listOf("external_id", "timetable_id", "lecture_id", "color", "color_index", "created_at", "updated_at"),
                    listOf(
                        tl.externalId(),
                        newTimetableId,
                        lectureId,
                        colorJson,
                        tl.int("color_index") ?: 0,
                        Timestamp.from(tl.instant("created_at") ?: now()),
                        Timestamp.from(tl.instant("updated_at") ?: now()),
                    ),
                )
                val newTimetableLectureId = lastInsertId()

                // 스냅샷과 현재 lecture 행의 차이만 customization으로 기록한다
                val customization = buildCustomization(tl, lectureId)
                if (customization != null) {
                    jdbc.update(
                        "INSERT INTO timetable_lecture_customization (timetable_lecture_id, course_title, instructor, credit, remark, class_place_and_time) VALUES (?, ?, ?, ?, ?, ?)",
                        newTimetableLectureId,
                        customization["course_title"],
                        customization["instructor"],
                        (customization["credit"] as? Number)?.toInt(),
                        customization["remark"],
                        customization["class_place_and_time"],
                    )
                }
            }
            count++
        }
        log.info("timetable 이관: {}건", count)
    }

    // lecture_id NULL(custom 강의)이거나 스냅샷이 현재 lecture와 다르면 customization 기록
    private fun buildCustomization(
        tl: Document,
        lectureId: Long?,
    ): Map<String, Any?>? {
        if (lectureId == null) {
            return mapOf(
                "course_title" to (tl.string("course_title") ?: ""),
                "instructor" to tl.string("instructor"),
                "credit" to tl.get("credit"),
                "remark" to tl.string("remark"),
                "class_place_and_time" to (tl.get("class_time_json")?.toString()),
            )
        }
        val current =
            jdbc
                .query(
                    "SELECT course_title, instructor, credit, remark, class_place_and_time FROM lecture WHERE id = ?",
                    org.springframework.jdbc.core.RowMapper { rs, _ ->
                        mapOf(
                            "course_title" to rs.getString("course_title"),
                            "instructor" to rs.getString("instructor"),
                            "credit" to rs.getObject("credit"),
                            "remark" to rs.getString("remark"),
                            "class_place_and_time" to rs.getString("class_place_and_time"),
                        )
                    },
                    lectureId,
                ).firstOrNull() ?: return null
        val diff =
            mutableMapOf<String, Any?>()
        val currentCourseTitle = current?.get("course_title")
        val currentInstructor = current?.get("instructor")
        val currentCredit = current?.get("credit")
        val currentRemark = current?.get("remark")
        val currentClassTime = current?.get("class_place_and_time")
        if (tl.string("course_title") != currentCourseTitle) diff["course_title"] = tl.string("course_title")
        if (tl.string("instructor") != currentInstructor) diff["instructor"] = tl.string("instructor")
        if (tl.get("credit") != currentCredit) diff["credit"] = tl.get("credit")
        if (tl.string("remark") != currentRemark) diff["remark"] = tl.string("remark")
        if (tl.get("class_time_json")?.toString() != currentClassTime) {
            diff["class_place_and_time"] = tl.get("class_time_json")?.toString()
        }
        return diff.takeIf { it.isNotEmpty() }
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
        collection("themes").find().forEach { doc ->
            val userId = idMaps.get("user", doc.string("user_id") ?: "") ?: return@forEach
            val origin = doc.get("origin") as? Document
            insert(
                "theme",
                listOf(
                    "external_id",
                    "user_id",
                    "name",
                    "color_list",
                    "is_custom",
                    "status",
                    "origin_theme_id",
                    "origin_author_id",
                    "publish_name",
                    "author_anonymous",
                    "download_count",
                    "created_at",
                    "updated_at",
                ),
                listOf(
                    doc.externalId(),
                    userId,
                    doc.string("name") ?: "",
                    doc.get("colors")?.toString(),
                    doc.bool("is_custom"),
                    doc.string("status") ?: "PRIVATE",
                    origin?.string("originId")?.let { idMaps.get("theme", it) },
                    origin?.string("authorId")?.let { idMaps.get("user", it) },
                    (doc.get("publishInfo") as? Document)?.string("publishName"),
                    (doc.get("publishInfo") as? Document)?.get("authorAnonymous") as? Boolean,
                    (doc.get("publishInfo") as? Document)?.get("downloads") as? Number ?: 0,
                    Timestamp.from(doc.instant("createdAt") ?: now()),
                    Timestamp.from(doc.instant("updatedAt") ?: now()),
                ),
            )
            idMaps.put("theme", doc.externalId(), lastInsertId())
            count++
        }
        log.info("theme 이관: {}건", count)
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
