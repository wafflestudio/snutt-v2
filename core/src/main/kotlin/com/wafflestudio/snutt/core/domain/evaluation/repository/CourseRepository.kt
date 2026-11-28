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
import kotlin.reflect.KProperty1

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
            SELECT course_id, id
            FROM (
                SELECT id,
                       course_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY course_id
                           ORDER BY year DESC, semester DESC, updated_at DESC, id DESC
                       ) AS rn
                FROM lecture
                WHERE course_id IN (:courseIds)
            ) ranked
            WHERE rn = 1
        ) latest ON latest.course_id = c.id
        LEFT JOIN lecture l ON l.id = latest.id
        SET c.latest_lecture_id = l.id,
            c.title = COALESCE(l.course_title, c.title),
            c.title_en = l.course_title_en,
            c.instructor_en = l.instructor_en,
            c.department = l.department,
            c.department_en = l.department_en,
            c.academic_year = l.academic_year,
            c.academic_year_en = l.academic_year_en,
            c.category = l.category,
            c.category_en = l.category_en,
            c.category_pre2025 = l.category_pre2025,
            c.classification = l.classification,
            c.classification_en = l.classification_en,
            c.credit = l.credit
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
                    val alternatives = mutableListOf<Predicate>()
                    when (val intent = SearchKeywordClassifier.classify(word, criteria.language)) {
                        KeywordIntent.Empty -> Unit
                        KeywordIntent.Major -> alternatives += path(Course::classification).`in`(listOf("전선", "전필"))
                        KeywordIntent.Graduate -> alternatives += path(Course::academicYear).`in`(SearchKeywordClassifier.GRADUATE_YEARS)
                        KeywordIntent.Undergraduate ->
                            alternatives +=
                                path(Course::academicYear).notIn(SearchKeywordClassifier.GRADUATE_YEARS)
                        KeywordIntent.PhysicalEducation -> alternatives += path(Course::category).equal("체육")
                        is KeywordIntent.Fuzzy -> text(alternatives, intent.keyword, true)
                        KeywordIntent.EnglishLecture -> text(alternatives, "영강", true)
                        KeywordIntent.MilitaryLeave -> text(alternatives, "군휴학", true)
                        KeywordIntent.Recommended -> text(alternatives, "권장과목", true)
                        is KeywordIntent.Place -> text(alternatives, intent.keyword, false)
                        is KeywordIntent.Plain -> text(alternatives, intent.keyword, false)
                    }
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
                    .from(entity(Course::class))
                    .where(and(*conditions.toTypedArray()))
                    .orderBy(path(Course::evalCount).desc(), path(Course::id).asc())
            }
        }.filterNotNull()

    private fun Jpql.lectureFilters(criteria: CourseSearchCriteria): List<Predicate> {
        val english = criteria.language == Language.EN
        val conditions = mutableListOf<Predicate>()
        criteria.department.takeIf { it.isNotEmpty() }?.let {
            conditions += path(if (english) Course::departmentEn else Course::department).`in`(it)
        }
        if (criteria.yearSemesters.isEmpty()) {
            criteria.credit.takeIf { it.isNotEmpty() }?.let { conditions += path(Course::credit).`in`(it) }
            criteria.academicYear.takeIf { it.isNotEmpty() }?.let {
                conditions += path(if (english) Course::academicYearEn else Course::academicYear).`in`(it)
            }
            criteria.classification.takeIf { it.isNotEmpty() }?.let {
                conditions += path(if (english) Course::classificationEn else Course::classification).`in`(it)
            }
            criteria.category.takeIf { it.isNotEmpty() }?.let {
                conditions += path(if (english) Course::categoryEn else Course::category).`in`(it)
            }
            criteria.categoryPre2025.takeIf { it.isNotEmpty() }?.let { conditions += path(Course::categoryPre2025).`in`(it) }
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
        conditions: MutableList<Predicate>,
        word: String,
        fuzzy: Boolean,
    ) {
        val pattern = if (fuzzy) word.toCharArray().joinToString("%", prefix = "%", postfix = "%") else "%$word%"
        conditions += path(Course::title).like(pattern)
        conditions += path(Course::instructor).like(if (fuzzy) word else pattern)
        conditions += path(Course::courseNumber).like(word)
        conditions += path(Course::titleEn).like(pattern)
        conditions += path(Course::instructorEn).like(if (fuzzy) word else pattern)
        if (fuzzy) {
            conditions += path(Course::category).like(pattern)
            conditions += path(Course::academicYear).equal(word)
            conditions += path(Course::classification).equal(word)
            val departmentPrefix =
                when (word.last()) {
                    '과', '부' -> word.dropLast(1)
                    '학' -> null
                    else -> word
                }
            departmentPrefix?.let { conditions += path(Course::department).like(it.toCharArray().joinToString("%", postfix = "%")) }
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
        return CourseVocabulary(
            classification = distinctStrings(if (english) Lecture::classificationEn else Lecture::classification),
            department = distinctStrings(if (english) Lecture::departmentEn else Lecture::department),
            academicYear = distinctStrings(if (english) Lecture::academicYearEn else Lecture::academicYear),
            credit =
                findAll {
                    jpql {
                        selectDistinct(path(Lecture::credit))
                            .from(entity(Lecture::class))
                            .orderBy(path(Lecture::credit).asc())
                    }
                }.filterNotNull(),
            category = distinctStrings(if (english) Lecture::categoryEn else Lecture::category),
            categoryPre2025 = distinctStrings(Lecture::categoryPre2025),
            semesters =
                findAll {
                    jpql {
                        selectNew<YearAndSemester>(path(Lecture::year), path(Lecture::semester))
                            .from(entity(Lecture::class))
                            .groupBy(path(Lecture::year), path(Lecture::semester))
                            .orderBy(path(Lecture::year).desc(), path(Lecture::semester).desc())
                    }
                }.filterNotNull(),
            updatedAt =
                findAll {
                    jpql {
                        select(max(path(Lecture::updatedAt))).from(entity(Lecture::class))
                    }
                }.firstOrNull(),
        )
    }

    private fun distinctStrings(property: KProperty1<Lecture, String?>): List<String> =
        findAll {
            jpql {
                selectDistinct(path(property))
                    .from(entity(Lecture::class))
                    .where(and(path(property).isNotNull(), path(property).notEqual("")))
                    .orderBy(path(property).asc())
            }
        }.filterNotNull()
}
