package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationStep
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.long
import com.wafflestudio.snutt.migration.oid
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.requireStr
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

@Component
class ThemeStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
    private val jsonMapper: JsonMapper,
) : MigrationStep(jdbc, context) {
    override val tables = listOf("user_preference", "published_theme", "timetable_theme")

    private data class SourceTheme(
        val document: Document,
        val id: Long,
        val userId: Long,
        val name: String,
        val palette: List<ColorSet>,
    ) {
        val downloaded get() = document.str("status") == "DOWNLOADED"
    }

    private data class Publication(
        val id: Long,
        val name: String,
        val palette: List<ColorSet>,
    )

    override fun run() {
        loadBuiltinPalettes()
        val ids = IdSequence(context.themePalettes.keys.max() + 1)
        val themes = mutableListOf<SourceTheme>()
        mongo.each("timetableTheme") { doc ->
            if (!doc.bool("isCustom")) return@each
            val userId = context.userIds[doc.oid("userId")]
            if (userId == null) {
                context.resolved(MigrationSupport.ResolutionReasons.THEME_USER_MISSING)
                if (doc.str("status") != "DOWNLOADED" && doc.doc("publishInfo")?.str("publishName") != null) {
                    context.resolved(MigrationSupport.ResolutionReasons.PUBLISHED_THEME_USER_MISSING)
                }
                return@each
            }
            val palette = doc.docs("colors").map { ColorSet(checkNotNull(it.str("bg")), checkNotNull(it.str("fg"))) }
            check(palette.size in 1..9) { "잘못된 팔레트: ${doc.id()}" }
            val id = ids.next()
            context.themeIds[doc.id()] = id
            context.themePalettes[id] = palette
            themes += SourceTheme(doc, id, userId, doc.requireStr("name"), palette)
        }
        val publicationIds = IdSequence()
        val publicationsBySource = mutableMapOf<String, Publication>()
        writer("timetable_theme", THEME_COLUMNS).use { out ->
            writer("published_theme", PUBLICATION_COLUMNS, parent = out).use { publications ->
                themes.filterNot { it.downloaded }.forEach { source ->
                    val d = source.document
                    out.add(
                        source.id,
                        source.userId,
                        source.name,
                        jsonMapper.writeValueAsString(source.palette),
                        null,
                        d.instant("createdAt").orNow().toSqlTimestamp(),
                        d.instant("updatedAt").orNow().toSqlTimestamp(),
                    )
                    val info = d.doc("publishInfo") ?: return@forEach
                    val name = info.str("publishName") ?: return@forEach
                    val publication = Publication(publicationIds.next(), name, source.palette)
                    publicationsBySource[d.id()] = publication
                    publications.add(
                        publication.id,
                        source.userId,
                        source.id,
                        name,
                        jsonMapper.writeValueAsString(source.palette),
                        info.bool("authorAnonymous"),
                        d.str("status") == "PUBLISHED",
                        info.long("downloads") ?: 0L,
                        d.instant("createdAt").orNow().toSqlTimestamp(),
                        d.instant("updatedAt").orNow().toSqlTimestamp(),
                    )
                }
                val archives = mutableMapOf<String, Publication>()
                val downloadsByUser = mutableMapOf<Pair<Long, Long>, Long>()
                writer("timetable_theme", THEME_COLUMNS, parent = publications).use { downloads ->
                    themes.filter { it.downloaded }.forEach { source ->
                        val d = source.document
                        val origin = d.doc("origin")
                        val originId = origin?.oid("originId")
                        val current = publicationsBySource[originId]
                        val publication =
                            if (current != null && current.name == source.name && current.palette == source.palette) {
                                current
                            } else {
                                val key = "${originId.orEmpty()}\u0000${source.name}\u0000${jsonMapper.writeValueAsString(source.palette)}"
                                archives.getOrPut(key) {
                                    context.resolved(MigrationSupport.ResolutionReasons.PUBLISHED_THEME_ARCHIVED)
                                    val archived = Publication(publicationIds.next(), source.name, source.palette)
                                    publications.add(
                                        archived.id,
                                        origin?.oid("authorId")?.let(context.userIds::get),
                                        null,
                                        archived.name,
                                        jsonMapper.writeValueAsString(archived.palette),
                                        true,
                                        false,
                                        0L,
                                        d.instant("createdAt").orNow().toSqlTimestamp(),
                                        d.instant("updatedAt").orNow().toSqlTimestamp(),
                                    )
                                    archived
                                }
                            }
                        val key = source.userId to publication.id
                        val previous = downloadsByUser[key]
                        if (previous != null) {
                            context.themeIds[d.id()] = previous
                            context.resolved(MigrationSupport.ResolutionReasons.THEME_DOWNLOAD_MERGED)
                        } else {
                            downloadsByUser[key] = source.id
                            downloads.add(
                                source.id,
                                source.userId,
                                publication.name,
                                jsonMapper.writeValueAsString(publication.palette),
                                publication.id,
                                d.instant("createdAt").orNow().toSqlTimestamp(),
                                d.instant("updatedAt").orNow().toSqlTimestamp(),
                            )
                        }
                    }
                }
            }
        }
        val defaults = mutableMapOf<Long, SourceTheme>()
        themes.forEach { source ->
            val previous = defaults[source.userId]
            if (previous == null || source.document.instant("updatedAt").orNow() >= previous.document.instant("updatedAt").orNow()) {
                defaults[source.userId] = source
            }
        }
        writer("user_preference", listOf("user_id", "default_theme_id")).use { out ->
            defaults.values.forEach { out.add(it.userId, context.themeIds.getValue(it.document.id())) }
        }
        log.info("테마 이관: {}건, 온라인 스냅샷 {}건", context.themeIds.size, publicationIds.peek() - 1)
    }

    private fun loadBuiltinPalettes() {
        jdbc.query("SELECT id, colors FROM timetable_theme WHERE builtin_code IS NOT NULL") { rs ->
            context.themePalettes[rs.getLong("id")] = jsonMapper.readValue<List<ColorSet>>(rs.getString("colors"))
        }
    }

    companion object {
        private val THEME_COLUMNS = listOf("id", "user_id", "name", "colors", "publication_id", "created_at", "updated_at")
        private val PUBLICATION_COLUMNS =
            listOf(
                "id",
                "author_id",
                "source_theme_id",
                "name",
                "colors",
                "author_anonymous",
                "listed",
                "download_count",
                "created_at",
                "updated_at",
            )
    }
}
