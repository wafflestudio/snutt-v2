package com.wafflestudio.snutt.v1compat.ev

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.search.KeywordIntent
import com.wafflestudio.snutt.core.common.search.SearchKeywordClassifier
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

data class LegacyCourseSearchCriteria(
    val query: String = "",
    val classification: List<String> = emptyList(),
    val credit: List<Int> = emptyList(),
    val academicYear: List<String> = emptyList(),
    val department: List<String> = emptyList(),
    val category: List<String> = emptyList(),
    val yearSemesters: List<YearAndSemester> = emptyList(),
)

data class LegacyCourseMetadata(
    val id: Long,
    val courseNumber: String,
    val instructor: String,
    val title: String,
    val department: String?,
    val credit: Int?,
    val academicYear: String?,
    val category: String?,
    val classification: String?,
    val evalCount: Long,
    val avgRating: Double?,
)

@Repository
class LegacyCourseRepository(
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    private val classifier =
        SearchKeywordClassifier(
            """^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""".toRegex(),
            """^(?:|#|\*)\d+(?:-\d+)?동$""".toRegex(),
        )

    fun get(courseId: Long): LegacyCourseMetadata = getByIds(listOf(courseId))[courseId] ?: throw SnuttException(ErrorType.COURSE_NOT_FOUND)

    fun getByIds(courseIds: Collection<Long>): Map<Long, LegacyCourseMetadata> {
        if (courseIds.isEmpty()) return emptyMap()
        return findAll {
            jpql {
                selectNew<LegacyCourseMetadata>(
                    path(Course::id),
                    path(Course::courseNumber),
                    path(Course::instructor),
                    path(Course::title),
                    path(Lecture::department),
                    path(Lecture::credit),
                    path(Lecture::academicYear),
                    path(Lecture::category),
                    path(Lecture::classification),
                    path(Course::evalCount),
                    path(Course::avgRating),
                ).from(
                    entity(Course::class),
                    leftJoin(Lecture::class).on(and(path(Lecture::courseId).equal(path(Course::id)), latest())),
                ).where(path(Course::id).`in`(courseIds))
            }
        }.filterNotNull().associateBy { it.id }
    }

    fun search(
        criteria: LegacyCourseSearchCriteria,
        page: Int,
        size: Int,
    ): List<LegacyCourseMetadata> {
        if (page < 0 || size <= 0 || page > Int.MAX_VALUE / size) throw SnuttException(ErrorType.INVALID_PARAMETER)
        return findAll(offset = page * size, limit = size) {
            jpql {
                selectNew<LegacyCourseMetadata>(
                    path(Course::id),
                    path(Course::courseNumber),
                    path(Course::instructor),
                    path(Course::title),
                    path(Lecture::department),
                    path(Lecture::credit),
                    path(Lecture::academicYear),
                    path(Lecture::category),
                    path(Lecture::classification),
                    path(Course::evalCount),
                    path(Course::avgRating),
                ).from(
                    entity(Course::class),
                    leftJoin(Lecture::class).on(and(path(Lecture::courseId).equal(path(Course::id)), latest())),
                ).where(and(*predicates(criteria).toTypedArray()))
                    .orderBy(path(Course::evalCount).desc(), path(Course::id).asc())
            }
        }.filterNotNull()
    }

    fun count(criteria: LegacyCourseSearchCriteria): Long =
        findAll {
            jpql {
                select(count(path(Course::id)))
                    .from(
                        entity(Course::class),
                        leftJoin(Lecture::class).on(and(path(Lecture::courseId).equal(path(Course::id)), latest())),
                    ).where(and(*predicates(criteria).toTypedArray()))
            }
        }.first()!!

    private fun Jpql.latest(): Predicate =
        notExists(
            jpql {
                val newer = entity(Lecture::class, "newer")
                select(value(1)).from(newer).where(
                    and(
                        newer.path(Lecture::courseId).equal(path(Lecture::courseId)),
                        or(
                            newer.path(Lecture::year).greaterThan(path(Lecture::year)),
                            and(
                                newer.path(Lecture::year).equal(path(Lecture::year)),
                                newer.path(Lecture::semester).greaterThan(path(Lecture::semester)),
                            ),
                            and(
                                newer.path(Lecture::year).equal(path(Lecture::year)),
                                newer.path(Lecture::semester).equal(path(Lecture::semester)),
                                newer.path(Lecture::updatedAt).greaterThan(path(Lecture::updatedAt)),
                            ),
                            and(
                                newer.path(Lecture::year).equal(path(Lecture::year)),
                                newer.path(Lecture::semester).equal(path(Lecture::semester)),
                                newer.path(Lecture::updatedAt).equal(path(Lecture::updatedAt)),
                                newer.path(Lecture::id).greaterThan(path(Lecture::id)),
                            ),
                        ),
                    ),
                )
            }.asSubquery(),
        )

    private fun Jpql.predicates(criteria: LegacyCourseSearchCriteria): List<Predicate> {
        val conditions = mutableListOf<Predicate>()
        criteria.department.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::department).`in`(it) }
        if (criteria.yearSemesters.isEmpty()) {
            criteria.credit.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::credit).`in`(it) }
            criteria.classification.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::classification).`in`(it) }
            criteria.academicYear.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::academicYear).`in`(it) }
            criteria.category.takeIf { it.isNotEmpty() }?.let { conditions += path(Lecture::category).`in`(it) }
        } else {
            conditions +=
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
                        criteria.classification.takeIf { it.isNotEmpty() }?.let { filters += scoped.path(Lecture::classification).`in`(it) }
                        criteria.academicYear.takeIf { it.isNotEmpty() }?.let { filters += scoped.path(Lecture::academicYear).`in`(it) }
                        criteria.category.takeIf { it.isNotEmpty() }?.let { filters += scoped.path(Lecture::category).`in`(it) }
                        select(scoped.path(Lecture::courseId)).from(scoped).where(and(*filters.toTypedArray()))
                    }.asSubquery(),
                )
        }
        criteria.query.split(' ').filter { it.isNotBlank() }.forEach { word ->
            val alternatives = mutableListOf<Predicate>()
            when (val intent = classifier.classify(word, Language.KO)) {
                KeywordIntent.Empty -> Unit
                KeywordIntent.Major -> alternatives += path(Lecture::classification).`in`(listOf("전선", "전필"))
                KeywordIntent.Graduate -> alternatives += path(Lecture::academicYear).`in`(GRADUATE_YEARS)
                KeywordIntent.Undergraduate -> alternatives += path(Lecture::academicYear).notIn(GRADUATE_YEARS)
                KeywordIntent.PhysicalEducation -> alternatives += path(Lecture::category).equal("체육")
                is KeywordIntent.Fuzzy -> fuzzy(alternatives, intent.keyword)
                KeywordIntent.EnglishLecture -> fuzzy(alternatives, "영강")
                KeywordIntent.MilitaryLeave -> fuzzy(alternatives, "군휴학")
                KeywordIntent.Recommended -> fuzzy(alternatives, "권장과목")
                is KeywordIntent.Place -> plain(alternatives, intent.keyword)
                is KeywordIntent.Plain -> plain(alternatives, intent.keyword)
            }
            if (alternatives.isNotEmpty()) conditions += or(*alternatives.toTypedArray())
        }
        return conditions
    }

    private fun Jpql.plain(
        conditions: MutableList<Predicate>,
        keyword: String,
    ) {
        conditions += path(Course::title).like("%$keyword%")
        conditions += path(Course::instructor).like("%$keyword%")
        conditions += path(Course::courseNumber).like(keyword)
    }

    private fun Jpql.fuzzy(
        conditions: MutableList<Predicate>,
        keyword: String,
    ) {
        val pattern = keyword.fold("%") { acc, character -> "$acc$character%" }
        conditions += path(Course::title).like(pattern)
        conditions += path(Lecture::category).like(pattern)
        conditions += path(Course::instructor).equal(keyword)
        conditions += path(Lecture::academicYear).equal(keyword)
        conditions += path(Lecture::classification).equal(keyword)
        when (keyword.last()) {
            '과', '부' -> conditions += path(Lecture::department).like(pattern.substring(1, pattern.length - 2))
            '학' -> Unit
            else -> conditions += path(Lecture::department).like(pattern.substring(1))
        }
    }

    companion object {
        private val GRADUATE_YEARS = listOf("석사", "박사", "석박사통합")
    }
}
