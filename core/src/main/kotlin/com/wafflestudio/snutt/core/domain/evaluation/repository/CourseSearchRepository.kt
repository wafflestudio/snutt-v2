package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
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
    private val classifier = SearchKeywordClassifier(placeRegex, buildingRegex)

    companion object {
        private val GRADUATE_YEARS = listOf("석사", "박사", "석박사통합")
        private val placeRegex = """^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""".toRegex()
        private val buildingRegex = """^(?:|#|\*)\d+(?:-\d+)?동$""".toRegex()
    }

    fun search(
        criteria: CourseSearchCriteria,
        cursor: CourseSearchCursor?,
        limit: Int,
        offset: Int? = null,
    ): List<Course> =
        findAll(offset = offset, limit = limit) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += criteriaPredicates(criteria)
                cursor?.let {
                    predicates +=
                        or(
                            path(Course::evalCount).lessThan(it.evalCount),
                            and(
                                path(Course::evalCount).equal(it.evalCount),
                                path(Course::id).greaterThan(it.courseId),
                            ),
                        )
                }
                select(entity(Course::class))
                    .from(entity(Course::class))
                    .where(and(*predicates.toTypedArray()))
                    .orderBy(path(Course::evalCount).desc(), path(Course::id).asc())
            }
        }.filterNotNull()

    fun count(criteria: CourseSearchCriteria): Long =
        findAll(offset = null, limit = 1) {
            jpql {
                select(count(path(Course::id)))
                    .from(entity(Course::class))
                    .where(and(*criteriaPredicates(criteria).toTypedArray()))
            }
        }.firstOrNull() ?: 0L

    private fun Jpql.criteriaPredicates(criteria: CourseSearchCriteria): List<Predicate> {
        val builder = mutableListOf<Predicate>()
        criteria.credit.takeIf { it.isNotEmpty() }?.let { builder += path(Course::credit).`in`(it) }
        criteria.academicYear.takeIf { it.isNotEmpty() }?.let { builder += path(Course::academicYear).`in`(it) }
        criteria.classification.takeIf { it.isNotEmpty() }?.let { builder += path(Course::classification).`in`(it) }
        criteria.department.takeIf { it.isNotEmpty() }?.let { builder += path(Course::department).`in`(it) }
        criteria.category.takeIf { it.isNotEmpty() }?.let { builder += path(Course::category).`in`(it) }
        queryPredicate(criteria.query)?.let { builder += it }

        if (criteria.yearSemesters.isNotEmpty()) {
            val semesterBuilder = mutableListOf<Predicate>()
            criteria.yearSemesters.forEach { (year, semester) ->
                semesterBuilder +=
                    and(
                        path(Lecture::year).equal(year),
                        path(Lecture::semester).equal(semester),
                    )
            }
            builder +=
                and(
                    path(Course::id).`in`(
                        jpql {
                            select(path(Lecture::courseId))
                                .from(entity(Lecture::class))
                                .where(
                                    and(
                                        or(*semesterBuilder.toTypedArray()),
                                        path(Lecture::courseId).isNotNull(),
                                    ),
                                )
                        }.asSubquery(),
                    ),
                )
        }

        return builder
    }

    private fun Jpql.queryPredicate(query: String?): Predicate? {
        if (query.isNullOrBlank()) return null
        val builder = mutableListOf<Predicate>()
        query.split(' ').filter { it.isNotBlank() }.forEach { keyword ->
            val or = mutableListOf<Predicate>()
            when (val intent = classifier.classify(keyword, Language.KO)) {
                KeywordIntent.Empty -> {}
                KeywordIntent.Major -> or += path(Course::classification).`in`(listOf("전선", "전필"))
                KeywordIntent.Graduate -> or += path(Course::academicYear).`in`(GRADUATE_YEARS)
                KeywordIntent.Undergraduate -> or += path(Course::academicYear).notIn(GRADUATE_YEARS)
                KeywordIntent.PhysicalEducation -> or += path(Course::category).equal("체육")
                is KeywordIntent.Fuzzy,
                KeywordIntent.EnglishLecture,
                KeywordIntent.MilitaryLeave,
                KeywordIntent.Recommended,
                -> fuzzyCoursePredicate(or, intent)

                is KeywordIntent.Place,
                is KeywordIntent.Plain,
                -> plainCoursePredicate(or, intent)
            }
            if (or.isNotEmpty()) builder += or(*or.toTypedArray())
        }
        return if (builder.isEmpty()) null else and(*builder.toTypedArray())
    }

    private fun Jpql.fuzzyCoursePredicate(
        or: MutableList<Predicate>,
        intent: KeywordIntent,
    ) {
        val keyword =
            when (intent) {
                is KeywordIntent.Fuzzy -> intent.keyword
                KeywordIntent.EnglishLecture -> "영강"
                KeywordIntent.MilitaryLeave -> "군휴학"
                KeywordIntent.Recommended -> "권장과목"
                else -> return
            }
        val fuzzy = keyword.fold("%") { acc, c -> "$acc$c%" }
        or += path(Course::title).like(fuzzy)
        or += path(Course::category).like(fuzzy)
        or += path(Course::instructor).equal(keyword)
        or += path(Course::academicYear).equal(keyword)
        or += path(Course::classification).equal(keyword)
        when (keyword.last()) {
            '과', '부' -> or += path(Course::department).like(fuzzy.substring(1, fuzzy.length - 2))
            '학' -> {}
            else -> or += path(Course::department).like(fuzzy.substring(1))
        }
    }

    private fun Jpql.plainCoursePredicate(
        or: MutableList<Predicate>,
        intent: KeywordIntent,
    ) {
        val keyword =
            when (intent) {
                is KeywordIntent.Place -> intent.keyword
                is KeywordIntent.Plain -> intent.keyword
                else -> return
            }
        or += path(Course::title).like("%$keyword%")
        or += path(Course::instructor).like("%$keyword%")
        or += path(Course::courseNumber).like(keyword)
    }
}
