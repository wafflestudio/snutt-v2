package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.search.KeywordIntent
import com.wafflestudio.snutt.core.common.search.SearchKeywordClassifier
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCursor
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

data class CourseVocabulary(
    val classification: List<String>,
    val department: List<String>,
    val academicYear: List<String>,
    val credit: List<Int>,
    val category: List<String>,
    val categoryPre2025: List<String>,
    val semesters: List<YearAndSemester>,
    val updatedAt: Instant?,
)

data class TakenLectureRow(
    val year: Int,
    val semester: Semester,
    val lecture: Lecture,
    val course: Course,
)

private data class CourseVocabularyRow(
    val classification: String?,
    val department: String?,
    val academicYear: String?,
    val credit: Int,
    val category: String?,
    val categoryPre2025: String?,
    val year: Int,
    val semester: Semester,
    val updatedAt: Instant?,
)

interface CourseRepository :
    JpaRepository<Course, Long>,
    KotlinJdslJpqlExecutor {
    fun findByCourseNumberAndInstructor(
        courseNumber: String,
        instructor: String,
    ): Course?

    fun findAllByCourseNumberIn(courseNumbers: Collection<String>): List<Course>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: Long): Course?

    @Modifying(flushAutomatically = true)
    @Query(
        value = """
        UPDATE course c
        LEFT JOIN (
            SELECT course_id, id, course_title
            FROM (
                SELECT id,
                       course_id,
                       course_title,
                       ROW_NUMBER() OVER (
                           PARTITION BY course_id
                           ORDER BY year DESC, semester DESC, updated_at DESC, id DESC
                       ) AS rn
                FROM lecture
                WHERE course_id IN (:courseIds)
            ) ranked
            WHERE rn = 1
        ) latest ON latest.course_id = c.id
        SET c.latest_lecture_id = latest.id, c.title = COALESCE(latest.course_title, c.title)
        WHERE c.id IN (:courseIds)
        """,
        nativeQuery = true,
    )
    fun refreshLatestLectures(courseIds: Collection<Long>): Int

    fun search(
        criteria: CourseSearchCriteria,
        cursor: CourseSearchCursor?,
        limit: Int,
    ): List<Course> =
        findAll(offset = 0, limit = limit) {
            jpql {
                val conditions = mutableListOf<Predicate>()
                conditions += lectureFilters(criteria)
                criteria.query.split(' ').filter { it.isNotBlank() }.forEach { word ->
                    val identities = mutableListOf<Predicate>()
                    val lectures = mutableListOf<Predicate>()
                    when (val intent = SearchKeywordClassifier.classify(word, criteria.language)) {
                        KeywordIntent.Empty -> Unit
                        KeywordIntent.Major -> lectures += path(Lecture::classification).`in`(listOf("전선", "전필"))
                        KeywordIntent.Graduate -> lectures += path(Lecture::academicYear).`in`(SearchKeywordClassifier.GRADUATE_YEARS)
                        KeywordIntent.Undergraduate -> lectures += path(Lecture::academicYear).notIn(SearchKeywordClassifier.GRADUATE_YEARS)
                        KeywordIntent.PhysicalEducation -> lectures += path(Lecture::category).equal("체육")
                        is KeywordIntent.Fuzzy -> text(identities, lectures, intent.keyword, true)
                        KeywordIntent.EnglishLecture -> text(identities, lectures, "영강", true)
                        KeywordIntent.MilitaryLeave -> text(identities, lectures, "군휴학", true)
                        KeywordIntent.Recommended -> text(identities, lectures, "권장과목", true)
                        is KeywordIntent.Place -> text(identities, lectures, intent.keyword, false)
                        is KeywordIntent.Plain -> text(identities, lectures, intent.keyword, false)
                    }
                    val alternatives = identities + lectures
                    if (alternatives.isNotEmpty()) conditions += or(*alternatives.toTypedArray())
                }
                cursor?.let {
                    conditions +=
                        or(
                            path(Course::evalCount).lessThan(it.evalCount),
                            and(path(Course::evalCount).equal(it.evalCount), path(Course::id).greaterThan(it.courseId)),
                        )
                }
                select(entity(Course::class))
                    .from(
                        entity(Course::class),
                        leftJoin(Lecture::class).on(path(Lecture::id).equal(path(Course::latestLectureId))),
                    ).where(and(*conditions.toTypedArray()))
                    .orderBy(path(Course::evalCount).desc(), path(Course::id).asc())
            }
        }.filterNotNull()

    private fun Jpql.lectureFilters(criteria: CourseSearchCriteria): List<Predicate> {
        val english = criteria.language == Language.EN
        val conditions = mutableListOf<Predicate>()
        criteria.department.takeIf { it.isNotEmpty() }?.let {
            conditions += path(if (english) Lecture::departmentEn else Lecture::department).`in`(it)
        }
        if (criteria.yearSemesters.isEmpty()) {
            criteria.credit.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::credit).`in`(it) }
            criteria.academicYear.takeIf { it.isNotEmpty() }?.let {
                conditions += path(if (english) Lecture::academicYearEn else Lecture::academicYear).`in`(it)
            }
            criteria.classification.takeIf { it.isNotEmpty() }?.let {
                conditions += path(if (english) Lecture::classificationEn else Lecture::classification).`in`(it)
            }
            criteria.category.takeIf { it.isNotEmpty() }?.let {
                conditions += path(if (english) Lecture::categoryEn else Lecture::category).`in`(it)
            }
            criteria.categoryPre2025.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::categoryPre2025).`in`(it) }
        } else {
            conditions += scopedLectures(criteria, english)
        }
        return conditions
    }

    private fun Jpql.scopedLectures(
        criteria: CourseSearchCriteria,
        english: Boolean,
    ): Predicate =
        path(Course::id).`in`(
            jpql {
                val scoped = entity(Lecture::class, "scoped")
                val filters = mutableListOf<Predicate>()
                filters +=
                    or(
                        *criteria.yearSemesters
                            .map {
                                and(scoped.path(Lecture::year).equal(it.year), scoped.path(Lecture::semester).equal(it.semester))
                            }.toTypedArray(),
                    )
                criteria.credit.takeIf { it.isNotEmpty() }?.let { filters += scoped.path(Lecture::credit).`in`(it) }
                criteria.academicYear.takeIf { it.isNotEmpty() }?.let {
                    filters += scoped.path(if (english) Lecture::academicYearEn else Lecture::academicYear).`in`(it)
                }
                criteria.classification.takeIf { it.isNotEmpty() }?.let {
                    filters += scoped.path(if (english) Lecture::classificationEn else Lecture::classification).`in`(it)
                }
                criteria.category.takeIf { it.isNotEmpty() }?.let {
                    filters += scoped.path(if (english) Lecture::categoryEn else Lecture::category).`in`(it)
                }
                criteria.categoryPre2025.takeIf { it.isNotEmpty() }?.let { filters += scoped.path(Lecture::categoryPre2025).`in`(it) }
                select(scoped.path(Lecture::courseId)).from(scoped).where(and(*filters.toTypedArray()))
            }.asSubquery(),
        )

    private fun Jpql.text(
        identities: MutableList<Predicate>,
        lectures: MutableList<Predicate>,
        word: String,
        fuzzy: Boolean,
    ) {
        val pattern = if (fuzzy) word.toCharArray().joinToString("%", prefix = "%", postfix = "%") else "%$word%"
        identities += path(Course::title).like(pattern)
        identities += path(Course::instructor).like(if (fuzzy) word else pattern)
        identities += path(Course::courseNumber).like(word)
        lectures += path(Lecture::courseTitle).like(pattern)
        lectures += path(Lecture::courseTitleEn).like(pattern)
        lectures += path(Lecture::instructorEn).like(if (fuzzy) word else pattern)
        if (fuzzy) {
            lectures += path(Lecture::category).like(pattern)
            lectures += path(Lecture::academicYear).equal(word)
            lectures += path(Lecture::classification).equal(word)
            val departmentPrefix =
                when (word.last()) {
                    '과', '부' -> word.dropLast(1)
                    '학' -> null
                    else -> word
                }
            departmentPrefix?.let { lectures += path(Lecture::department).like(it.toCharArray().joinToString("%", postfix = "%")) }
        }
    }

    fun findTakenLectures(
        userId: Long,
        semesters: Collection<YearAndSemester>,
    ): List<TakenLectureRow> {
        if (semesters.isEmpty()) return emptyList()
        return findAll {
            jpql {
                selectNew<TakenLectureRow>(
                    path(Timetable::year),
                    path(Timetable::semester),
                    entity(Lecture::class),
                    entity(Course::class),
                ).from(
                    entity(Timetable::class),
                    join(TimetableLecture::class).on(path(TimetableLecture::timetableId).equal(path(Timetable::id))),
                    join(Lecture::class).on(path(Lecture::id).equal(path(TimetableLecture::lectureId))),
                    join(Course::class).on(path(Course::id).equal(path(Lecture::courseId))),
                ).where(
                    and(
                        path(Timetable::userId).equal(userId),
                        or(
                            *semesters
                                .map {
                                    and(
                                        path(Timetable::year).equal(it.year),
                                        path(Timetable::semester).equal(it.semester),
                                    )
                                }.toTypedArray(),
                        ),
                    ),
                ).orderBy(
                    path(Timetable::year).desc(),
                    path(Timetable::semester).desc(),
                    path(TimetableLecture::id).asc(),
                )
            }
        }.filterNotNull()
    }

    fun getVocabulary(language: Language): CourseVocabulary {
        val english = language == Language.EN
        val rows =
            findAll {
                jpql {
                    selectNew<CourseVocabularyRow>(
                        path(if (english) Lecture::classificationEn else Lecture::classification),
                        path(if (english) Lecture::departmentEn else Lecture::department),
                        path(if (english) Lecture::academicYearEn else Lecture::academicYear),
                        path(Lecture::credit),
                        path(if (english) Lecture::categoryEn else Lecture::category),
                        path(Lecture::categoryPre2025),
                        path(Lecture::year),
                        path(Lecture::semester),
                        path(Lecture::updatedAt),
                    ).from(entity(Lecture::class))
                        .where(path(Lecture::courseId).isNotNull())
                }
            }.filterNotNull()

        val classification = sortedSetOf<String>()
        val department = sortedSetOf<String>()
        val academicYear = sortedSetOf<String>()
        val credit = sortedSetOf<Int>()
        val category = sortedSetOf<String>()
        val categoryPre2025 = sortedSetOf<String>()
        val semesters = hashSetOf<YearAndSemester>()
        var updatedAt: Instant? = null

        for (row in rows) {
            row.classification?.takeIf { it.isNotEmpty() }?.let(classification::add)
            row.department?.takeIf { it.isNotEmpty() }?.let(department::add)
            row.academicYear?.takeIf { it.isNotEmpty() }?.let(academicYear::add)
            credit.add(row.credit)
            row.category?.takeIf { it.isNotEmpty() }?.let(category::add)
            row.categoryPre2025?.takeIf { it.isNotEmpty() }?.let(categoryPre2025::add)
            semesters.add(YearAndSemester(row.year, row.semester))
            val rowUpdatedAt = row.updatedAt
            if (rowUpdatedAt != null && (updatedAt == null || rowUpdatedAt.isAfter(updatedAt))) {
                updatedAt = rowUpdatedAt
            }
        }

        return CourseVocabulary(
            classification = classification.toList(),
            department = department.toList(),
            academicYear = academicYear.toList(),
            credit = credit.toList(),
            category = category.toList(),
            categoryPre2025 = categoryPre2025.toList(),
            semesters = semesters.sortedWith(compareByDescending<YearAndSemester> { it.year }.thenByDescending { it.semester.value }),
            updatedAt = updatedAt,
        )
    }
}
