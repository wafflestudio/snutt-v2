package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.querydsl.core.BooleanBuilder
import com.querydsl.jpa.impl.JPAQueryFactory
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.search.KeywordIntent
import com.wafflestudio.snutt.core.common.search.SearchKeywordClassifier
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCursor
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.QCourse
import com.wafflestudio.snutt.core.domain.lecture.model.QLecture
import org.springframework.stereotype.Repository

@Repository
class CourseSearchRepository(
    private val queryFactory: JPAQueryFactory,
) {
    private val classifier = SearchKeywordClassifier(placeRegex, buildingRegex)
    private val course = QCourse.course

    companion object {
        private const val PAGE_SIZE = 20
        private val GRADUATE_YEARS = listOf("석사", "박사", "석박사통합")
        private val placeRegex = """^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""".toRegex()
        private val buildingRegex = """^(?:|#|\*)\d+(?:-\d+)?동$""".toRegex()
    }

    fun searchPage(
        criteria: CourseSearchCriteria,
        page: Int,
    ): List<Course> {
        val course = QCourse.course
        return queryFactory
            .selectFrom(course)
            .where(predicate(criteria))
            .orderBy(course.evalCount.desc(), course.id.asc())
            .offset(page.toLong() * PAGE_SIZE)
            .limit(PAGE_SIZE.toLong())
            .fetch()
    }

    fun search(
        criteria: CourseSearchCriteria,
        cursor: CourseSearchCursor?,
        limit: Int,
    ): List<Course> =
        queryFactory
            .selectFrom(course)
            .where(
                predicate(criteria),
                cursor?.let {
                    course.evalCount.lt(it.evalCount).or(
                        course.evalCount.eq(it.evalCount).and(course.id.gt(it.courseId)),
                    )
                },
            ).orderBy(course.evalCount.desc(), course.id.asc())
            .limit(limit.toLong())
            .fetch()

    fun count(criteria: CourseSearchCriteria): Long =
        queryFactory
            .select(QCourse.course.count())
            .from(QCourse.course)
            .where(predicate(criteria))
            .fetchOne() ?: 0L

    private fun predicate(criteria: CourseSearchCriteria): com.querydsl.core.types.Predicate? {
        val builder = BooleanBuilder()
        criteria.credit.takeIf { it.isNotEmpty() }?.let { builder.and(course.credit.`in`(it)) }
        criteria.academicYear.takeIf { it.isNotEmpty() }?.let { builder.and(course.academicYear.`in`(it)) }
        criteria.classification.takeIf { it.isNotEmpty() }?.let { builder.and(course.classification.`in`(it)) }
        criteria.department.takeIf { it.isNotEmpty() }?.let { builder.and(course.department.`in`(it)) }
        criteria.category.takeIf { it.isNotEmpty() }?.let { builder.and(course.category.`in`(it)) }
        queryPredicate(criteria.query)?.let { builder.and(it) }

        if (criteria.yearSemesters.isNotEmpty()) {
            val lecture = QLecture.lecture
            val semesterBuilder = BooleanBuilder()
            criteria.yearSemesters.forEach { (year, semester) ->
                semesterBuilder.or(lecture.year.eq(year).and(lecture.semester.eq(semester)))
            }
            builder.and(
                course.id.`in`(
                    com.querydsl.jpa.JPAExpressions
                        .select(lecture.courseId)
                        .from(lecture)
                        .where(semesterBuilder.value, lecture.courseId.isNotNull),
                ),
            )
        }

        return builder.value
    }

    private fun queryPredicate(query: String?): com.querydsl.core.types.Predicate? {
        if (query.isNullOrBlank()) return null
        val builder = BooleanBuilder()
        query.split(' ').filter { it.isNotBlank() }.forEach { keyword ->
            val or = BooleanBuilder()
            when (val intent = classifier.classify(keyword, Language.KO)) {
                KeywordIntent.Empty -> {}
                KeywordIntent.Major -> or.or(course.classification.`in`("전선", "전필"))
                KeywordIntent.Graduate -> or.or(course.academicYear.`in`(GRADUATE_YEARS))
                KeywordIntent.Undergraduate -> or.or(course.academicYear.notIn(GRADUATE_YEARS))
                KeywordIntent.PhysicalEducation -> or.or(course.category.eq("체육"))
                is KeywordIntent.Fuzzy,
                KeywordIntent.EnglishLecture,
                KeywordIntent.MilitaryLeave,
                KeywordIntent.Recommended,
                -> fuzzyCoursePredicate(or, intent)

                is KeywordIntent.Place,
                is KeywordIntent.Plain,
                -> plainCoursePredicate(or, intent)
            }
            if (or.hasValue()) builder.and(or.value)
        }
        return builder.value
    }

    private fun fuzzyCoursePredicate(
        or: BooleanBuilder,
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
        or.or(course.title.like(fuzzy))
        or.or(course.category.like(fuzzy))
        or.or(course.instructor.eq(keyword))
        or.or(course.academicYear.eq(keyword))
        or.or(course.classification.eq(keyword))
        when (keyword.last()) {
            '과', '부' -> or.or(course.department.like(fuzzy.substring(1, fuzzy.length - 2)))
            '학' -> {}
            else -> or.or(course.department.like(fuzzy.substring(1)))
        }
    }

    private fun plainCoursePredicate(
        or: BooleanBuilder,
        intent: KeywordIntent,
    ) {
        val keyword =
            when (intent) {
                is KeywordIntent.Place -> intent.keyword
                is KeywordIntent.Plain -> intent.keyword
                else -> return
            }
        or.or(course.title.like("%$keyword%"))
        or.or(course.instructor.like("%$keyword%"))
        or.or(course.courseNumber.like(keyword))
    }
}
