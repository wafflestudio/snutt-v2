package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.dbl
import com.wafflestudio.snutt.migration.doc
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.oids
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.requireInt
import com.wafflestudio.snutt.migration.requireStr
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.strings
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@Component
class CatalogStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
    private val jsonMapper: JsonMapper,
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
            "diary_question_target",
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
                    doc.requireStr("buildingNumber"),
                    doc.requireStr("buildingNameKor"),
                    doc.requireStr("buildingNameEng"),
                    doc.requireStr("campus"),
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
        return jsonMapper.writeValueAsString(mapOf("latitude" to latitude, "longitude" to longitude))
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
                    doc.docs("registrationPeriods").map { period ->
                        mapOf(
                            "date" to MigrationSupport.toLocalDate(checkNotNull(period.instant("date"))).toString(),
                            "vacantSeatRegistrationTimes" to
                                period.docs("vacantSeatRegistrationTimes").map { slot ->
                                    mapOf("startMinute" to slot.requireInt("startMinute"), "endMinute" to slot.requireInt("endMinute"))
                                },
                            "phase" to period.requireStr("phase"),
                        )
                    }
                out.add(ids.next(), doc.requireInt("year"), doc.requireInt("semester"), jsonMapper.writeValueAsString(periods), now, now)
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
                "os_type",
                "min_version",
                "max_version",
                "value",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            mongo.each("clientConfig") { doc ->
                val name = doc.requireStr("name")
                val value = doc.requireStr("value")
                val createdAt = doc.instant("createdAt").orNow().toSqlTimestamp()
                val updatedAt = doc.instant("updatedAt").orNow().toSqlTimestamp()
                listOf(
                    OsType.IOS to (doc.str("minIosVersion") to doc.str("maxIosVersion")),
                    OsType.ANDROID to (doc.str("minAndroidVersion") to doc.str("maxAndroidVersion")),
                ).forEach { (osType, versions) ->
                    out.add(ids.next(), name, osType.name, versions.first, versions.second, value, createdAt, updatedAt)
                }
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
                    doc.requireStr("key"),
                    doc.requireStr("imageOriginUri"),
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
                out.add(id, doc.requireStr("name"), doc.bool("active"), now, now)
            }
        }
        alignAutoIncrement("diary_daily_class_type", typeIds.peek())

        val questionIds =
            com.wafflestudio.snutt.migration
                .IdSequence()
        val targetIds =
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
                "active",
                "created_at",
                "updated_at",
            ),
        ).use { out ->
            writer(
                "diary_question_target",
                listOf(
                    "id",
                    "question_id",
                    "daily_class_type_id",
                    "created_at",
                    "updated_at",
                ),
                parent = out,
            ).use { targetOut ->
                mongo.each("diaryQuestion") { doc ->
                    val id = questionIds.next()
                    context.diaryQuestionIds[doc.id()] = id
                    val targets = doc.oids("targetDailyClassTypeIds").map { context.diaryClassTypeIds.getValue(it) }
                    val now = Instant.now().toSqlTimestamp()
                    out.add(
                        id,
                        doc.requireStr("question"),
                        doc.requireStr("shortQuestion"),
                        jsonMapper.writeValueAsString(doc.strings("answers")),
                        jsonMapper.writeValueAsString(doc.strings("shortAnswers")),
                        doc.bool("active"),
                        now,
                        now,
                    )
                    targets.forEach { targetId ->
                        targetOut.add(targetIds.next(), id, targetId, now, now)
                    }
                }
            }
        }
        alignAutoIncrement("diary_question", questionIds.peek())
        alignAutoIncrement("diary_question_target", targetIds.peek())
        log.info(
            "카탈로그 이관: 일기장 종류 {}건, 질문 {}건",
            context.diaryClassTypeIds.size,
            context.diaryQuestionIds.size,
        )
    }
}
