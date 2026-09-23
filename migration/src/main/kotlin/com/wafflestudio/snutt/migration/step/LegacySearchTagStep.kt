package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.EvSource
import com.wafflestudio.snutt.migration.MigrationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class LegacySearchTagStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val ev: EvSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "legacytag"
    override val tables = emptyList<String>()

    override fun run() {
        jdbc.execute("DELETE FROM legacy_search_tag")

        var count = 0
        writer(
            "legacy_search_tag",
            listOf("id", "group_name", "group_ordering", "group_color", "name", "ordering", "int_value", "string_value"),
        ).use { out ->
            ev.jdbc.query(
                "SELECT t.id, g.name AS group_name, g.ordering AS group_ordering, g.color AS group_color, " +
                    "t.name, t.ordering, t.int_value, t.string_value " +
                    "FROM tag t JOIN tag_group g ON g.id = t.tag_group_id WHERE g.name <> 'main'",
            ) { rs ->
                out.add(
                    rs.getLong("id"),
                    rs.getString("group_name"),
                    rs.getInt("group_ordering"),
                    rs.getString("group_color"),
                    rs.getString("name"),
                    rs.getInt("ordering"),
                    rs.getObject("int_value") as? Int,
                    rs.getString("string_value"),
                )
                count++
            }
        }
        log.info("구 검색 태그 이관: {}건", count)
    }
}
