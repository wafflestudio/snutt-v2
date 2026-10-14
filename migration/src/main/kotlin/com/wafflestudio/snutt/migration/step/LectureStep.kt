package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.LectureSnapshot
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MigrationStep
import com.wafflestudio.snutt.migration.MigrationSupport
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.nullIfBlank
import com.wafflestudio.snutt.migration.requireInt
import com.wafflestudio.snutt.migration.requireStr
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class LectureStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : MigrationStep(jdbc, context) {
    override val tables = listOf("lecture_class_time", "lecture_registration_status", "lecture")

    override fun run() {
        val ids = IdSequence()
        val classTimeIds = IdSequence()
        val offerings = HashMap<String, Long>(256_000)
        val now = Instant.now().toSqlTimestamp()

        writer("lecture", LECTURE_COLUMNS).use { lectures ->
            writer("lecture_class_time", CLASS_TIME_COLUMNS, parent = lectures).use { classTimes ->
                writer("lecture_registration_status", STATUS_COLUMNS, parent = lectures).use { statuses ->
                    mongo.each("lectures") { doc ->
                        val externalId = doc.id()
                        val offeringKey = offeringKey(doc)
                        val existing = offerings[offeringKey]
                        if (existing != null) {
                            context.lectureIds[externalId] = existing
                            context.resolved(MigrationSupport.ResolutionReasons.LECTURE_DUPLICATE)
                            return@each
                        }

                        val id = ids.next()
                        offerings[offeringKey] = id
                        context.lectureIds[externalId] = id
                        context.lectureSemesters += doc.requireInt("year") to doc.requireInt("semester")

                        val instructor = doc.str("instructor").nullIfBlank()
                        val courseId =
                            context.courseIds[context.courseKey(doc.str("course_number"), instructor)]
                        val places = doc.docs("class_time_json")

                        lectures.add(
                            id,
                            courseId,
                            doc.int("year"),
                            doc.int("semester"),
                            doc.requireStr("course_number"),
                            doc.requireStr("lecture_number"),
                            doc.requireStr("course_title"),
                            instructor,
                            doc.str("department").nullIfBlank(),
                            doc.str("academic_year").nullIfBlank(),
                            doc.str("category").nullIfBlank(),
                            doc.str("categoryPre2025").nullIfBlank(),
                            doc.str("classification").nullIfBlank(),
                            doc.str("course_title_en").nullIfBlank(),
                            doc.str("instructor_en").nullIfBlank(),
                            doc.str("department_en").nullIfBlank(),
                            academicYearEn(doc.str("academic_year_en")),
                            doc.str("category_en").nullIfBlank(),
                            doc.str("classification_en").nullIfBlank(),
                            doc.str("remark_en").nullIfBlank(),
                            doc.requireInt("credit"),
                            doc.requireInt("quota"),
                            doc.int("freshmanQuota"),
                            doc.str("remark").nullIfBlank(),
                            now,
                            now,
                        )

                        statuses.add(
                            id,
                            doc.requireInt("registrationCount"),
                            doc.bool("wasFull"),
                            now,
                        )

                        places.forEach { place ->
                            classTimes.add(
                                classTimeIds.next(),
                                id,
                                place.requireInt("day"),
                                place.str("place"),
                                place.requireInt("startMinute"),
                                place.requireInt("endMinute"),
                            )
                        }

                        context.lectureSnapshots[id] = doc.toSnapshot(places)
                    }
                }
            }
        }
        log.info("강의 이관: {}건, 수업 시간 {}건", offerings.size, classTimeIds.peek() - 1)
    }

    private fun offeringKey(doc: Document): String =
        listOf(
            doc.int("year").toString(),
            doc.int("semester").toString(),
            doc.requireStr("course_number"),
            doc.requireStr("lecture_number"),
        ).joinToString("\u0000")

    private fun Document.toSnapshot(places: List<Document>) =
        LectureSnapshot(
            courseTitle = str("course_title"),
            instructor = context.intern(str("instructor").nullIfBlank()),
            credit = int("credit"),
            remark = str("remark").nullIfBlank(),
            academicYear = context.intern(str("academic_year").nullIfBlank()),
            category = context.intern(str("category").nullIfBlank()),
            classification = context.intern(str("classification").nullIfBlank()),
            categoryPre2025 = context.intern(str("categoryPre2025").nullIfBlank()),
            classTimeKey = context.intern(classTimeKey(places))!!,
        )

    companion object {
        private val ACADEMIC_YEAR_NUMBER = Regex("^[0-9]+$")

        private fun academicYearEn(value: String?): String? =
            value.nullIfBlank()?.let { if (ACADEMIC_YEAR_NUMBER.matches(it)) "Year $it" else it }

        fun classTimeKey(places: List<Document>): String =
            places.joinToString("|") { place ->
                listOf(
                    place.requireInt("day"),
                    place.requireStr("place"),
                    place.requireInt("startMinute"),
                    place.requireInt("endMinute"),
                ).joinToString(",")
            }

        private val LECTURE_COLUMNS =
            listOf(
                "id",
                "course_id",
                "year",
                "semester",
                "course_number",
                "lecture_number",
                "course_title",
                "instructor",
                "department",
                "academic_year",
                "category",
                "category_pre2025",
                "classification",
                "course_title_en",
                "instructor_en",
                "department_en",
                "academic_year_en",
                "category_en",
                "classification_en",
                "remark_en",
                "credit",
                "quota",
                "freshman_quota",
                "remark",
                "created_at",
                "updated_at",
            )
        private val STATUS_COLUMNS = listOf("lecture_id", "registration_count", "was_full", "updated_at")
        private val CLASS_TIME_COLUMNS = listOf("id", "lecture_id", "day", "place", "start_minute", "end_minute")
    }
}
