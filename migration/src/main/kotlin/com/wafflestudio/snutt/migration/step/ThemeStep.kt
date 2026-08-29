package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.Json
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class ThemeStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "theme"
    override val tables = listOf("published_theme", "theme")

    override fun run() {
        val themes = ArrayList<Document>()
        mongo.each("timetableTheme") { doc ->
            if (doc.bool("isCustom")) themes.add(doc) else context.resolved("내장 테마는 v2 시드 행이 대신하므로 제외")
        }

        reseedBuiltinThemes()

        val ids = IdSequence(start = 7L)
        themes.forEach { context.themeIds[it.id()] = ids.next() }

        writer("theme", THEME_COLUMNS).use { out ->
            themes.forEach { doc ->
                val userId = context.userIds[doc.oid("userId")]
                if (userId == null) {
                    context.resolved("사용자가 없는 테마를 제외")
                    context.themeIds.remove(doc.id())
                    return@forEach
                }
                val createdAt = doc.instant("createdAt").orNow()
                val updatedAt = doc.instant("updatedAt").orNow()
                out.add(
                    context.themeIds.getValue(doc.id()),
                    userId,
                    doc.str("name").orEmpty(),
                    Json.writeRequired(doc.docs("colors").map { it.toColorSet() }),
                    createdAt.toSqlTimestamp(),
                    updatedAt.toSqlTimestamp(),
                )
            }
        }

        linkOrigins(themes)
        val published = migratePublished(themes)
        alignAutoIncrement("theme", ids.peek())
        log.info("테마 이관: {}건 (공개 {}건)", context.themeIds.size, published)
    }

    private fun reseedBuiltinThemes() {
        // --truncate로 지워진 내장 시드 행을 복구한다. V1__init.sql의 시드와 동일 값
        BUILTIN_THEMES.forEach { (_, builtinType, name, colors) ->
            jdbc.update(
                "INSERT IGNORE INTO theme (id, user_id, builtin_type, name, colors, created_at, updated_at) " +
                    "VALUES (?, NULL, ?, ?, ?, NOW(6), NOW(6))",
                builtinType + 1L,
                builtinType,
                name,
                Json.writeRequired(colors.map { mapOf("backgroundColor" to it, "foregroundColor" to "#ffffff") }),
            )
        }
    }

    private fun linkOrigins(themes: List<Document>) {
        themes.forEach { doc ->
            val themeId = context.themeIds[doc.id()] ?: return@forEach
            val origin = doc.doc("origin") ?: return@forEach
            val originThemeId = origin.oid("originId")?.let(context.themeIds::get)
            val originAuthorId = origin.oid("authorId")?.let(context.userIds::get)
            if (originThemeId == null && originAuthorId == null) return@forEach
            jdbc.update(
                "UPDATE theme SET origin_theme_id = ?, origin_author_id = ? WHERE id = ?",
                originThemeId,
                originAuthorId,
                themeId,
            )
        }
    }

    private fun migratePublished(themes: List<Document>): Int {
        val ids = IdSequence()
        var count = 0
        writer(
            "published_theme",
            listOf("id", "theme_id", "publish_name", "author_anonymous", "download_count", "created_at", "updated_at"),
        ).use { out ->
            themes.forEach { doc ->
                val themeId = context.themeIds[doc.id()] ?: return@forEach
                val publishInfo = doc.doc("publishInfo") ?: return@forEach
                val publishName = publishInfo.str("publishName") ?: return@forEach
                val updatedAt = doc.instant("updatedAt").orNow().toSqlTimestamp()
                out.add(
                    ids.next(),
                    themeId,
                    publishName,
                    publishInfo.bool("authorAnonymous"),
                    publishInfo.int("downloads")?.toLong() ?: 0L,
                    updatedAt,
                    updatedAt,
                )
                count++
            }
        }
        alignAutoIncrement("published_theme", ids.peek())
        return count
    }

    private fun Document.toColorSet(): Map<String, String?> = mapOf("backgroundColor" to str("bg"), "foregroundColor" to str("fg"))

    companion object {
        private val THEME_COLUMNS =
            listOf("id", "user_id", "name", "colors", "created_at", "updated_at")

        private val BUILTIN_THEMES =
            listOf(
                BuiltinTheme(
                    "builtin-snutt",
                    0,
                    "SNUTT",
                    listOf("#E54459", "#F58D3D", "#FAC42D", "#A6D930", "#2BC267", "#1BD0C8", "#1D99E8", "#4F48C4", "#AF56B3"),
                ),
                BuiltinTheme(
                    "builtin-fall",
                    1,
                    "가을",
                    listOf("#B82E31", "#DB701C", "#EAA32A", "#C6C013", "#3A856E", "#19B2AC", "#3994CE", "#3F3A9C", "#924396"),
                ),
                BuiltinTheme(
                    "builtin-modern",
                    2,
                    "모던",
                    listOf("#F0652A", "#F5AD3E", "#998F36", "#89C291", "#266F55", "#13808F", "#366689", "#432920", "#D82F3D"),
                ),
                BuiltinTheme(
                    "builtin-blossom",
                    3,
                    "벚꽃",
                    listOf("#FD79A8", "#FEC9DD", "#FEB0CC", "#FE93BF", "#E9B1D0", "#C67D97", "#BB8EA7", "#BDB4BF", "#E16597"),
                ),
                BuiltinTheme(
                    "builtin-ice",
                    4,
                    "얼음",
                    listOf("#AABDCF", "#C0E9E8", "#66B6CA", "#015F95", "#A8D0DB", "#66B6CA", "#62A9D1", "#20363D", "#6D8A96"),
                ),
                BuiltinTheme(
                    "builtin-lawn",
                    5,
                    "잔디",
                    listOf("#4FBEAA", "#9FC1A4", "#5A8173", "#84AEB1", "#266F55", "#D0E0C4", "#59886D", "#476060", "#3D7068"),
                ),
            )

        private data class BuiltinTheme(
            val externalId: String,
            val builtinType: Int,
            val name: String,
            val colors: List<String>,
        )
    }
}
