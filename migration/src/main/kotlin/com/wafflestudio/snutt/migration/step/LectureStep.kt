package com.wafflestudio.snutt.migration.step

import com.wafflestudio.snutt.migration.AbstractMigrationStep
import com.wafflestudio.snutt.migration.IdSequence
import com.wafflestudio.snutt.migration.LectureSnapshot
import com.wafflestudio.snutt.migration.MigrationContext
import com.wafflestudio.snutt.migration.MongoSource
import com.wafflestudio.snutt.migration.bool
import com.wafflestudio.snutt.migration.docs
import com.wafflestudio.snutt.migration.id
import com.wafflestudio.snutt.migration.instant
import com.wafflestudio.snutt.migration.int
import com.wafflestudio.snutt.migration.orNow
import com.wafflestudio.snutt.migration.str
import com.wafflestudio.snutt.migration.toSqlTimestamp
import org.bson.Document
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class LectureStep(
    jdbc: JdbcTemplate,
    context: MigrationContext,
    private val mongo: MongoSource,
) : AbstractMigrationStep(jdbc, context) {
    override val name = "lecture"
    override val tables = listOf("lecture_class_time", "lecture")

    override fun run() {
        val ids = IdSequence()
        val classTimeIds = IdSequence()
        val offerings = HashMap<String, Long>(256_000)
        var classTimeCount = 0L

        writer("lecture", LECTURE_COLUMNS).use { lectures ->
            writer("lecture_class_time", CLASS_TIME_COLUMNS, parent = lectures).use { classTimes ->
                mongo.each("lectures") { doc ->
                    val externalId = doc.id()
                    val offeringKey = offeringKey(doc)
                    val existing = offerings[offeringKey]
                    if (existing != null) {
                        context.lectureIds[externalId] = existing
                        context.resolved("같은 (연도, 학기, 교과목번호, 분반)의 강의가 중복되어 하나로 합침")
                        return@each
                    }

                    val id = ids.next()
                    offerings[offeringKey] = id
                    context.lectureIds[externalId] = id

                    val instructor = context.intern(doc.str("instructor"))
                    val courseId =
                        context.courseIds[context.courseKey(doc.str("course_number"), instructor)]
                    val createdAt = doc.instant("created_at").orNow()
                    val places = doc.docs("class_time_json")

                    lectures.add(
                        id,
                        externalId,
                        courseId,
                        doc.int("year"),
                        doc.int("semester"),
                        doc.str("course_number").orEmpty(),
                        doc.str("lecture_number").orEmpty(),
                        doc.str("course_title").orEmpty(),
                        instructor,
                        context.intern(doc.str("department")),
                        context.intern(doc.str("academic_year")),
                        context.intern(doc.str("category")),
                        context.intern(doc.str("categoryPre2025")),
                        context.intern(doc.str("classification")),
                        doc.str("course_title_en"),
                        doc.str("instructor_en"),
                        context.intern(doc.str("department_en")),
                        context.intern(doc.str("academic_year_en")),
                        context.intern(doc.str("category_en")),
                        context.intern(doc.str("classification_en")),
                        doc.str("remark_en"),
                        doc.int("credit") ?: 0,
                        doc.int("quota") ?: 0,
                        doc.int("freshmanQuota"),
                        doc.str("remark"),
                        doc.int("registrationCount") ?: 0,
                        doc.bool("wasFull"),
                        createdAt.toSqlTimestamp(),
                        createdAt.toSqlTimestamp(),
                    )

                    places.forEach { place ->
                        classTimes.add(
                            classTimeIds.next(),
                            id,
                            place.int("day") ?: 0,
                            place.str("place"),
                            place.int("startMinute") ?: 0,
                            place.int("endMinute") ?: 0,
                        )
                        classTimeCount++
                    }

                    context.lectureSnapshots[id] = doc.toSnapshot(places)
                }
            }
        }
        alignAutoIncrement("lecture", ids.peek())
        alignAutoIncrement("lecture_class_time", classTimeIds.peek())
        log.info("강의 이관: {}건, 수업 시간 {}건", offerings.size, classTimeCount)
    }

    private fun offeringKey(doc: Document): String =
        listOf(
            doc.int("year").toString(),
            doc.int("semester").toString(),
            doc.str("course_number").orEmpty(),
            doc.str("lecture_number").orEmpty(),
        ).joinToString("\u0000")

    private fun Document.toSnapshot(places: List<Document>) =
        LectureSnapshot(
            courseTitle = str("course_title"),
            instructor = context.intern(str("instructor")),
            credit = int("credit"),
            remark = str("remark"),
            academicYear = context.intern(str("academic_year")),
            category = context.intern(str("category")),
            classification = context.intern(str("classification")),
            categoryPre2025 = context.intern(str("categoryPre2025")),
            classTimeKey = context.intern(classTimeKey(places))!!,
        )

    companion object {
        fun classTimeKey(places: List<Document>): String =
            places.joinToString("|") { place ->
                listOf(
                    place.int("day") ?: 0,
                    place.str("place").orEmpty(),
                    place.int("startMinute") ?: 0,
                    place.int("endMinute") ?: 0,
                ).joinToString(",")
            }

        private val LECTURE_COLUMNS =
            listOf(
                "id",
                "external_id",
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
                "registration_count",
                "was_full",
                "created_at",
                "updated_at",
            )
        private val CLASS_TIME_COLUMNS = listOf("id", "lecture_id", "day", "place", "start_minute", "end_minute")
    }
}
