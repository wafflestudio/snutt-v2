package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.querydsl.core.BooleanBuilder
import com.querydsl.jpa.impl.JPAQueryFactory
import com.wafflestudio.snutt.core.domain.evaluation.dto.CourseSearchCriteria
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.evaluation.model.QCourse
import com.wafflestudio.snutt.core.domain.lecture.model.QLecture
import org.springframework.stereotype.Repository

@Repository
class CourseSearchRepository(
    private val queryFactory: JPAQueryFactory,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private val GRADUATE_YEARS = listOf("석사", "박사", "석박사통합")
        private val MAJOR_CLASSIFICATIONS = listOf("전공선택", "전공필수")
    }

    fun search(criteria: CourseSearchCriteria): List<Course> {
        val course = QCourse.course
        return queryFactory
            .selectFrom(course)
            .where(predicate(criteria))
            .orderBy(course.evalCount.desc(), course.id.asc())
            .offset(criteria.page.toLong() * PAGE_SIZE)
            .limit(PAGE_SIZE.toLong())
            .fetch()
    }

    fun count(criteria: CourseSearchCriteria): Long =
        queryFactory
            .select(QCourse.course.count())
            .from(QCourse.course)
            .where(predicate(criteria))
            .fetchOne() ?: 0L

    private fun predicate(criteria: CourseSearchCriteria): com.querydsl.core.types.Predicate? {
        val course = QCourse.course
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
        val course = QCourse.course
        val builder = BooleanBuilder()
        query.split(' ').filter { it.isNotBlank() }.forEach { keyword ->
            val fuzzy = keyword.fold("%") { acc, c -> "$acc$c%" }
            val or = BooleanBuilder()
            when {
                keyword == "전공" -> or.or(course.classification.`in`(MAJOR_CLASSIFICATIONS))
                keyword == "체육" -> or.or(course.category.eq("체육"))
                keyword in listOf("석박", "대학원") -> or.or(course.academicYear.`in`(GRADUATE_YEARS))
                keyword in listOf("학부", "학사") -> or.or(course.academicYear.notIn(GRADUATE_YEARS))
                keyword.any { it in '가'..'힣' } -> {
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
                else -> {
                    or.or(course.title.like("%$keyword%"))
                    or.or(course.instructor.like("%$keyword%"))
                    or.or(course.courseNumber.like(keyword))
                }
            }
            builder.and(or.value)
        }
        return builder.value
    }
}
