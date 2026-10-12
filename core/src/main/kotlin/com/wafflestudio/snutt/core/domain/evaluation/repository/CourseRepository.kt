package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.search.KeywordIntent
import com.wafflestudio.snutt.core.common.search.SearchKeywordClassifier
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCursor
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
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
                            .where(path(Lecture::courseId).isNotNull())
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
                            .where(path(Lecture::courseId).isNotNull())
                            .groupBy(path(Lecture::year), path(Lecture::semester))
                            .orderBy(path(Lecture::year).desc(), path(Lecture::semester).desc())
                    }
                }.filterNotNull(),
            updatedAt =
                findAll {
                    jpql {
                        select(max(path(Lecture::updatedAt))).from(entity(Lecture::class)).where(path(Lecture::courseId).isNotNull())
                    }
                }.firstOrNull(),
        )
    }

    private fun distinctStrings(property: KProperty1<Lecture, String?>): List<String> =
        findAll {
            jpql {
                selectDistinct(path(property))
                    .from(entity(Lecture::class))
                    .where(and(path(Lecture::courseId).isNotNull(), path(property).isNotNull(), path(property).notEqual("")))
                    .orderBy(path(property).asc())
            }
        }.filterNotNull()
}
