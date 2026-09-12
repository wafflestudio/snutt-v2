package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.entity.Entity
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.search.KeywordIntent
import com.wafflestudio.snutt.core.common.search.SearchKeywordClassifier
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCursor
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class CourseSearchRepository(
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    private val classifier = SearchKeywordClassifier(PLACE, BUILDING)

    fun search(
        criteria: CourseSearchCriteria,
        cursor: CourseSearchCursor?,
        limit: Int,
    ): List<Course> =
        findAll(offset = 0, limit = limit) {
            jpql {
                val conditions = mutableListOf<Predicate>()
                val filters = lectureFilters(criteria)
                if (filters.isNotEmpty()) conditions += matchingLectures(filters)
                criteria.query.split(' ').filter { it.isNotBlank() }.forEach { word ->
                    val identities = mutableListOf<Predicate>()
                    val lectures = mutableListOf<Predicate>()
                    when (val intent = classifier.classify(word, criteria.language)) {
                        KeywordIntent.Empty -> Unit
                        KeywordIntent.Major -> lectures += path(Lecture::classification).`in`(listOf("전선", "전필"))
                        KeywordIntent.Graduate -> lectures += path(Lecture::academicYear).`in`(GRADUATE_YEARS)
                        KeywordIntent.Undergraduate -> lectures += path(Lecture::academicYear).notIn(GRADUATE_YEARS)
                        KeywordIntent.PhysicalEducation -> lectures += path(Lecture::category).equal("체육")
                        is KeywordIntent.Fuzzy -> text(identities, lectures, intent.keyword, true)
                        KeywordIntent.EnglishLecture -> text(identities, lectures, "영강", true)
                        KeywordIntent.MilitaryLeave -> text(identities, lectures, "군휴학", true)
                        KeywordIntent.Recommended -> text(identities, lectures, "권장과목", true)
                        is KeywordIntent.Place -> text(identities, lectures, intent.keyword, false)
                        is KeywordIntent.Plain -> text(identities, lectures, intent.keyword, false)
                    }
                    if (lectures.isNotEmpty()) identities += matchingLectures(filters + or(*lectures.toTypedArray()))
                    if (identities.isNotEmpty()) conditions += or(*identities.toTypedArray())
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

    fun findLatestLectures(courseIds: Collection<Long>): List<Lecture> {
        if (courseIds.isEmpty()) return emptyList()
        return findAll {
            jpql {
                val lecture = entity(Lecture::class)
                select(lecture).from(lecture).where(
                    and(
                        path(Lecture::courseId).`in`(courseIds),
                        notExists(
                            jpql {
                                val newer = entity(Lecture::class, "newer")
                                select(value(1)).from(newer).where(
                                    and(
                                        newer.path(Lecture::courseId).equal(path(Lecture::courseId)),
                                        newerThan(newer, lecture),
                                    ),
                                )
                            }.asSubquery(),
                        ),
                    ),
                )
            }
        }.filterNotNull()
    }

    private fun Jpql.matchingLectures(conditions: List<Predicate>): Predicate =
        path(Course::id).`in`(
            jpql {
                select(path(Lecture::courseId)).from(entity(Lecture::class)).where(and(*conditions.toTypedArray()))
            }.asSubquery(),
        )

    private fun Jpql.lectureFilters(criteria: CourseSearchCriteria): List<Predicate> {
        val conditions = mutableListOf<Predicate>()
        val english = criteria.language == Language.EN
        criteria.credit.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::credit).`in`(it) }
        criteria.academicYear.takeIf { it.isNotEmpty() }?.let {
            conditions +=
                path(if (english) Lecture::academicYearEn else Lecture::academicYear).`in`(it)
        }
        criteria.classification.takeIf { it.isNotEmpty() }?.let {
            conditions +=
                path(if (english) Lecture::classificationEn else Lecture::classification).`in`(it)
        }
        criteria.department.takeIf { it.isNotEmpty() }?.let {
            conditions +=
                path(if (english) Lecture::departmentEn else Lecture::department).`in`(it)
        }
        criteria.category.takeIf { it.isNotEmpty() }?.let {
            conditions +=
                path(if (english) Lecture::categoryEn else Lecture::category).`in`(it)
        }
        criteria.categoryPre2025.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::categoryPre2025).`in`(it) }
        if (criteria.yearSemesters.isNotEmpty()) {
            conditions +=
                or(
                    *criteria.yearSemesters
                        .map {
                            and(path(Lecture::year).equal(it.year), path(Lecture::semester).equal(it.semester))
                        }.toTypedArray(),
                )
        }
        return conditions
    }

    private fun Jpql.text(
        identities: MutableList<Predicate>,
        lectures: MutableList<Predicate>,
        word: String,
        fuzzy: Boolean,
    ) {
        val pattern = if (fuzzy) word.fold("%") { acc, character -> "$acc$character%" } else "%$word%"
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
            when (word.last()) {
                '과', '부' -> lectures += path(Lecture::department).like(pattern.substring(1, pattern.length - 2))
                '학' -> Unit
                else -> lectures += path(Lecture::department).like(pattern.substring(1))
            }
        }
    }

    private fun Jpql.newerThan(
        newer: Entity<Lecture>,
        previous: Entity<Lecture>,
    ): Predicate =
        or(
            newer.path(Lecture::year).greaterThan(previous.path(Lecture::year)),
            and(
                newer.path(Lecture::year).equal(previous.path(Lecture::year)),
                newer.path(Lecture::semester).greaterThan(previous.path(Lecture::semester)),
            ),
            and(
                newer.path(Lecture::year).equal(previous.path(Lecture::year)),
                newer.path(Lecture::semester).equal(previous.path(Lecture::semester)),
                newer.path(Lecture::updatedAt).greaterThan(previous.path(Lecture::updatedAt)),
            ),
            and(
                newer.path(Lecture::year).equal(previous.path(Lecture::year)),
                newer.path(Lecture::semester).equal(previous.path(Lecture::semester)),
                newer.path(Lecture::updatedAt).equal(previous.path(Lecture::updatedAt)),
                newer.path(Lecture::id).greaterThan(previous.path(Lecture::id)),
            ),
        )

    companion object {
        private val GRADUATE_YEARS = listOf("석사", "박사", "석박사통합")
        private val PLACE = """^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""".toRegex()
        private val BUILDING = """^(?:|#|\*)\d+(?:-\d+)?동$""".toRegex()
    }
}
