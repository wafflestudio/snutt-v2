package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.MigrationContext
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

@Component
class ThemeStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
    private val jsonMapper: JsonMapper,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "theme"
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
        reseedBuiltins()
        val ids = IdSequence(7)
        val themes = mutableListOf<SourceTheme>()
        mongo.each("timetableTheme") { doc ->
            if (!doc.bool("isCustom")) return@each
            val userId = context.userIds[doc.oid("userId")]
            if (userId == null) {
                context.resolved("사용자가 없는 테마를 제외")
                return@each
            }
            val palette = doc.docs("colors").map { ColorSet(checkNotNull(it.str("bg")), checkNotNull(it.str("fg"))) }
            check(palette.size in 1..9) { "잘못된 팔레트: ${doc.id()}" }
            val id = ids.next()
            context.themeIds[doc.id()] = id
            themes += SourceTheme(doc, id, userId, doc.requireStr("name"), palette)
        }
        writer("timetable_theme", THEME_COLUMNS).use { out ->
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
                context.themePalettes[source.id] = source.palette
            }
        }
        val publicationIds = IdSequence()
        val publicationsBySource = mutableMapOf<String, Publication>()
        writer("published_theme", PUBLICATION_COLUMNS).use { publications ->
            themes.filterNot { it.downloaded }.forEach { source ->
                val d = source.document
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
                                context.resolved("기존 다운로드 내용을 비공개 스냅샷으로 보존")
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
                        context.resolved("동일한 온라인 테마의 중복 다운로드를 합침")
                    } else {
                        downloadsByUser[key] = source.id
                        downloads.add(
                            source.id,
                            source.userId,
                            null,
                            null,
                            publication.id,
                            d.instant("createdAt").orNow().toSqlTimestamp(),
                            d.instant("updatedAt").orNow().toSqlTimestamp(),
                        )
                        context.themePalettes[source.id] = publication.palette
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
        alignAutoIncrement("timetable_theme", ids.peek())
        alignAutoIncrement("published_theme", publicationIds.peek())
        log.info("테마 이관: {}건, 온라인 스냅샷 {}건", context.themeIds.size, publicationIds.peek() - 1)
    }

    private fun reseedBuiltins() {
        BUILTINS.forEachIndexed { index, builtin ->
            val palette = builtin.third.map { ColorSet(it, "#ffffff") }
            jdbc.update(
                "INSERT INTO timetable_theme (id,user_id,builtin_code,name,colors,created_at,updated_at) " +
                    "VALUES (?,NULL,?,?,?,NOW(6),NOW(6)) ON DUPLICATE KEY UPDATE id=id",
                index + 1L,
                builtin.first,
                builtin.second,
                jsonMapper.writeValueAsString(palette),
            )
            context.themePalettes[index + 1L] = palette
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
        private val BUILTINS =
            listOf(
                Triple(
                    "snutt",
                    "SNUTT",
                    listOf("#E54459", "#F58D3D", "#FAC42D", "#A6D930", "#2BC267", "#1BD0C8", "#1D99E8", "#4F48C4", "#AF56B3"),
                ),
                Triple(
                    "fall",
                    "가을",
                    listOf("#B82E31", "#DB701C", "#EAA32A", "#C6C013", "#3A856E", "#19B2AC", "#3994CE", "#3F3A9C", "#924396"),
                ),
                Triple(
                    "modern",
                    "모던",
                    listOf("#F0652A", "#F5AD3E", "#998F36", "#89C291", "#266F55", "#13808F", "#366689", "#432920", "#D82F3D"),
                ),
                Triple(
                    "blossom",
                    "벚꽃",
                    listOf("#FD79A8", "#FEC9DD", "#FEB0CC", "#FE93BF", "#E9B1D0", "#C67D97", "#BB8EA7", "#BDB4BF", "#E16597"),
                ),
                Triple(
                    "ice",
                    "얼음",
                    listOf("#AABDCF", "#C0E9E8", "#66B6CA", "#015F95", "#A8D0DB", "#66B6CA", "#62A9D1", "#20363D", "#6D8A96"),
                ),
                Triple(
                    "lawn",
                    "잔디",
                    listOf("#4FBEAA", "#9FC1A4", "#5A8173", "#84AEB1", "#266F55", "#D0E0C4", "#59886D", "#476060", "#3D7068"),
                ),
            )
    }
}
