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
            if (doc.bool("isCustom")) themes.add(doc) else context.resolved("내장 테마 행은 v2에서 서비스가 합성하므로 제외")
        }

        val ids = IdSequence()
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
                    doc.id(),
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
            listOf("id", "external_id", "user_id", "name", "colors", "created_at", "updated_at")
    }
}
