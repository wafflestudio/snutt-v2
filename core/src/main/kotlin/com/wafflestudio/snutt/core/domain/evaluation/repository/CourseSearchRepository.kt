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
                select(entity(Lecture::class))
                    .from(
                        entity(Lecture::class),
                        join(Course::class).on(path(Course::latestLectureId).equal(path(Lecture::id))),
                    ).where(path(Course::id).`in`(courseIds))
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
}
