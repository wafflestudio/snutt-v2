package com.wafflestudio.snutt.parity.seed

import com.wafflestudio.snutt.parity.env.LegacyStores
import com.wafflestudio.snutt.parity.env.LoadedLegacyData
import com.wafflestudio.snutt.parity.env.ParityDataSource

/** 코드로 쓴 시드([ParitySeed])를 v1 Mongo 문서와 EV MySQL 행으로 넣는다. */
object SeedDataSource : ParityDataSource {
    override val name = "seed"

    private val data: LegacyData by lazy { ParitySeed.build() }

    override fun load(stores: LegacyStores): LoadedLegacyData {
        data.mongo.forEach { (collection, documents) ->
            // insertMany는 문서에 _id를 채워 넣으므로 시드 객체를 그대로 넘기지 않는다.
            if (documents.isNotEmpty()) stores.mongo.getCollection(collection).insertMany(documents.map { org.bson.Document(it) })
        }
        data.evRows.groupBy { it.table }.forEach { (table, rows) ->
            val columns =
                rows
                    .first()
                    .values.keys
                    .toList()
            val sql = "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${columns.joinToString { "?" }})"
            stores.ev.batchUpdate(sql, rows.map { row -> columns.map { row.values[it] }.toTypedArray() })
        }
        return LoadedLegacyData(data.aliases, data.users)
    }
}
