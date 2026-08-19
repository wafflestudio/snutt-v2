package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.Json
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.dbl
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.oids
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.strings
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class CatalogStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "catalog"
    override val tables =
        listOf(
            "coursebook",
            "lecture_building",
            "semester_registration_period",
            "client_config",
            "popup",
            "diary_question",
            "diary_daily_class_type",
        )

    override fun run() {
        migrateCoursebooks()
        migrateLectureBuildings()
        migrateRegistrationPeriods()
        migrateClientConfigs()
        migratePopups()
        migrateDiaryDefinitions()
    }

    private fun migrateCoursebooks() {
        val ids =
            com.wafflestudio.snutt.migration
                .IdSequence()
        writer("coursebook", listOf("id", "year", "semester", "created_at", "updated_at")).use { out ->
            mongo.each("coursebooks") { doc ->
                val updatedAt = doc.instant("updated_at").orNow()
                out.add(ids.next(), doc.int("year"), doc.int("semester"), updatedAt.toSqlTimestamp(), updatedAt.toSqlTimestamp())
            }
        }
        alignAutoIncrement("coursebook", ids.peek())
    }

    private fun migrateLectureBuildings() {
        val ids =
            com.wafflestudio.snutt.migration
                .IdSequence()
        writer(
            "lecture_building",
            listOf(
                "id",
                "building_number",
                "building_name_kor",
                "building_name_eng",
                "campus",
                "location_in_dms",
                "location_in_decimal",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            mongo.each("lectureBuilding") { doc ->
                val now = Instant.now().toSqlTimestamp()
                out.add(
                    ids.next(),
                    doc.str("buildingNumber") ?: "",
                    doc.str("buildingNameKor") ?: "",
                    doc.str("buildingNameEng") ?: "",
                    doc.str("campus") ?: "GWANAK",
                    doc.doc("locationInDMS")?.toGeoJson(),
                    doc.doc("locationInDecimal")?.toGeoJson(),
                    now,
                    now,
                )
            }
        }
        alignAutoIncrement("lecture_building", ids.peek())
    }

    private fun Document.toGeoJson(): String? {
        val latitude = dbl("latitude") ?: return null
        val longitude = dbl("longitude") ?: return null
        return Json.writeRequired(mapOf("latitude" to latitude, "longitude" to longitude))
    }

    private fun migrateRegistrationPeriods() {
        val ids =
            com.wafflestudio.snutt.migration
                .IdSequence()
        writer(
            "semester_registration_period",
            listOf("id", "year", "semester", "registration_period_list", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("semesterRegistrationPeriod") { doc ->
                val now = Instant.now().toSqlTimestamp()
                val periods =
                    doc.let { root ->
                        (root["registrationPeriods"] as? List<*>).orEmpty().filterIsInstance<Document>().map { period ->
                            mapOf(
                                "date" to period.instant("date")?.let(MigrationSupport::toLocalDate)?.toString(),
                                "vacantSeatRegistrationTimes" to
                                    (period["vacantSeatRegistrationTimes"] as? List<*>)
                                        .orEmpty()
                                        .filterIsInstance<Document>()
                                        .map { slot ->
                                            mapOf("startMinute" to slot.int("startMinute"), "endMinute" to slot.int("endMinute"))
                                        },
                                "phase" to (period.str("phase") ?: "CURRENT_STUDENT"),
                            )
                        }
                    }
                out.add(ids.next(), doc.int("year"), doc.int("semester"), Json.writeRequired(periods), now, now)
            }
        }
        alignAutoIncrement("semester_registration_period", ids.peek())
    }

    private fun migrateClientConfigs() {
        val ids =
            com.wafflestudio.snutt.migration
                .IdSequence()
        writer(
            "client_config",
            listOf(
                "id",
                "name",
                "value",
                "min_ios_version",
                "max_ios_version",
                "min_android_version",
                "max_android_version",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            mongo.each("clientConfig") { doc ->
                out.add(
                    ids.next(),
                    doc.str("name") ?: "",
                    doc.str("value") ?: "",
                    doc.str("minIosVersion"),
                    doc.str("maxIosVersion"),
                    doc.str("minAndroidVersion"),
                    doc.str("maxAndroidVersion"),
                    doc.instant("createdAt").orNow().toSqlTimestamp(),
                    doc.instant("updatedAt").orNow().toSqlTimestamp(),
                )
            }
        }
        alignAutoIncrement("client_config", ids.peek())
    }

    private fun migratePopups() {
        val ids =
            com.wafflestudio.snutt.migration
                .IdSequence()
        writer(
            "popup",
            listOf("id", "popup_key", "image_origin_uri", "link_url", "hidden_days", "created_at", "updated_at"),
        ).use { out ->
            mongo.each("popup") { doc ->
                out.add(
                    ids.next(),
                    doc.str("key") ?: "",
                    doc.str("imageOriginUri") ?: "",
                    doc.str("linkUrl"),
                    doc.int("hiddenDays"),
                    doc.instant("createdAt").orNow().toSqlTimestamp(),
                    doc.instant("updatedAt").orNow().toSqlTimestamp(),
                )
            }
        }
        alignAutoIncrement("popup", ids.peek())
    }

    private fun migrateDiaryDefinitions() {
        val typeIds =
            com.wafflestudio.snutt.migration
                .IdSequence()
        writer("diary_daily_class_type", listOf("id", "name", "active", "created_at", "updated_at")).use { out ->
            mongo.each("diaryDailyClassType") { doc ->
                val id = typeIds.next()
                context.diaryClassTypeIds[doc.id()] = id
                val now = Instant.now().toSqlTimestamp()
                out.add(id, doc.id(), doc.str("name") ?: "", doc.bool("active"), now, now)
            }
        }
        alignAutoIncrement("diary_daily_class_type", typeIds.peek())

        val questionIds =
            com.wafflestudio.snutt.migration
                .IdSequence()
        writer(
            "diary_question",
            listOf(
                "id",
                "question",
                "short_question",
                "answer_list",
                "short_answer_list",
                "target_daily_class_type_id_list",
                "active",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            mongo.each("diaryQuestion") { doc ->
                val id = questionIds.next()
                context.diaryQuestionIds[doc.id()] = id
                val targets = doc.oids("targetDailyClassTypeIds").mapNotNull { context.diaryClassTypeIds[it] }
                val now = Instant.now().toSqlTimestamp()
                out.add(
                    id,
                    doc.id(),
                    doc.str("question") ?: "",
                    doc.str("shortQuestion") ?: "",
                    Json.writeRequired(doc.strings("answers")),
                    Json.writeRequired(doc.strings("shortAnswers")),
                    Json.writeRequired(targets),
                    doc.bool("active"),
                    now,
                    now,
                )
            }
        }
        alignAutoIncrement("diary_question", questionIds.peek())
        log.info(
            "카탈로그 이관: 일기장 종류 {}건, 질문 {}건",
            context.diaryClassTypeIds.size,
            context.diaryQuestionIds.size,
        )
    }
}
