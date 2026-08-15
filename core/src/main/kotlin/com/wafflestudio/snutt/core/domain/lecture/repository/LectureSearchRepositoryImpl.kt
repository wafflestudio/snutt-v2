package com.wafflestudio.snutt.core.domain.lecture.repository

import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.core.types.dsl.Expressions
import com.querydsl.core.types.dsl.StringExpression
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.QLecture
import com.wafflestudio.snutt.core.domain.lecture.model.QLectureClassTime
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Repository

@Repository
class LectureSearchRepositoryImpl(
    private val queryFactory: JPAQueryFactory,
    ratingJoinViewProvider: ObjectProvider<LectureRatingJoinView>,
) : LectureSearchRepository {
    private val ratingJoinView = ratingJoinViewProvider.ifAvailable
    private val lecture = QLecture.lecture
    private val classTime = QLectureClassTime.lectureClassTime

    companion object {
        private val placeRegex = """^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""".toRegex()
        private val buildingRegex = """^(?:|#|\*)\d+(?:-\d+)?동$""".toRegex()
    }

    override fun search(criteria: LectureSearchCriteria): List<Lecture> {
        val query = queryFactory.selectFrom(lecture)
        applySort(query, criteria.sort)
        return applyFilters(query, criteria)
            .offset(criteria.offset)
            .limit(criteria.limit.toLong())
            .fetch()
    }

    private fun applySort(
        query: JPAQuery<Lecture>,
        sort: LectureSort,
    ) {
        when (sort) {
            LectureSort.DEFAULT -> query.orderBy(lecture.id.asc())

            LectureSort.RATING_DESC, LectureSort.COUNT_DESC ->
                checkNotNull(ratingJoinView) { "LectureRatingJoinView implementation is missing" }
                    .applyOrderBy(query, sort)
        }
    }

    private fun applyFilters(
        query: JPAQuery<Lecture>,
        criteria: LectureSearchCriteria,
    ): JPAQuery<Lecture> {
        val predicates =
            mutableListOf<BooleanExpression>(
                lecture.year.eq(criteria.year),
                lecture.semester.eq(criteria.semester),
            )

        criteria.query?.split(' ')?.forEach { keyword -> keywordPredicate(keyword)?.let(predicates::add) }
        criteria.classification?.takeIf { it.isNotEmpty() }?.let {
            predicates +=
                koOrEn(lecture.classification, lecture.classificationEn, it)
        }
        criteria.credit?.takeIf { it.isNotEmpty() }?.let { predicates += lecture.credit.`in`(it) }
        criteria.courseNumber?.takeIf { it.isNotEmpty() }?.let { predicates += lecture.courseNumber.`in`(it) }
        criteria.academicYear?.takeIf { it.isNotEmpty() }?.let { predicates += koOrEn(lecture.academicYear, lecture.academicYearEn, it) }
        criteria.department?.takeIf { it.isNotEmpty() }?.let { predicates += koOrEn(lecture.department, lecture.departmentEn, it) }

        val category = criteria.category.orEmpty().filter(String::isNotEmpty)
        val categoryPre2025 = criteria.categoryPre2025.orEmpty().filter(String::isNotEmpty)
        if (category.isNotEmpty() || categoryPre2025.isNotEmpty()) {
            predicates +=
                listOfNotNull(
                    category.takeIf { it.isNotEmpty() }?.let { koOrEn(lecture.category, lecture.categoryEn, it) },
                    categoryPre2025.takeIf { it.isNotEmpty() }?.let { lecture.categoryPre2025.`in`(it) },
                ).reduce(BooleanExpression::or)
        }

        criteria.times?.takeIf { it.isNotEmpty() }?.let { predicates += timesCoveredPredicate(it) }
        criteria.timesToExclude?.takeIf { it.isNotEmpty() }?.let { predicates += timesExcludedPredicate(it) }

        criteria.etcTags.orEmpty().forEach { etcTag ->
            when (etcTag) {
                "E" -> predicates += remarkMatches(".*ⓔ.*")
                "MO" -> predicates += remarkMatches(".*ⓜⓞ.*")
                "R" -> predicates += remarkMatches(".*권장과목.*")
            }
        }
        return query.where(*predicates.toTypedArray())
    }

    private fun keywordPredicate(keyword: String): BooleanExpression? =
        when {
            keyword.isEmpty() -> null
            keyword == "전공" -> lecture.classification.`in`("전선", "전필")
            keyword in listOf("석박", "대학원") -> lecture.academicYear.`in`("석사", "박사", "석박사통합")
            keyword in listOf("학부", "학사") -> lecture.academicYear.notIn("석사", "박사", "석박사통합")
            keyword == "체육" -> lecture.category.eq("체육")
            keyword in listOf("영강", "영어강의") -> remarkMatches(".*ⓔ.*")
            keyword in listOf("군휴학", "군휴학원격") -> remarkMatches(".*ⓜⓞ.*")
            keyword == "권장과목" -> remarkMatches(".*권장과목.*")
            placeRegex.matches(keyword) || buildingRegex.matches(keyword) ->
                placePredicate(keyword.replace("동", "").uppercase())

            keyword.hasKorean() -> koreanKeywordPredicate(keyword)
            else -> nonKoreanKeywordPredicate(keyword)
        }

    private fun koreanKeywordPredicate(keyword: String): BooleanExpression {
        val fuzzyKeyword = fuzzyPattern(keyword)
        val departmentPredicate =
            when (keyword.last()) {
                '과', '부' -> regexMatches(lecture.department, "^${fuzzyPattern(keyword.dropLast(1))}")
                '학' -> null
                else -> regexMatches(lecture.department, "^$fuzzyKeyword")
            }
        return listOfNotNull(
            regexMatches(lecture.courseTitle, fuzzyKeyword),
            regexMatches(lecture.category, fuzzyKeyword),
            exactlyMatches(lecture.instructor, keyword),
            exactlyMatches(lecture.academicYear, keyword),
            exactlyMatches(lecture.classification, keyword),
            departmentPredicate,
        ).reduce(BooleanExpression::or)
    }

    private fun koOrEn(
        ko: com.querydsl.core.types.dsl.StringPath,
        en: com.querydsl.core.types.dsl.StringPath,
        values: List<String>,
    ): BooleanExpression = ko.`in`(values).or(en.`in`(values))

    private fun nonKoreanKeywordPredicate(keyword: String): BooleanExpression =
        listOfNotNull(
            regexMatches(lecture.courseTitle, regexEscape(keyword)),
            regexMatches(lecture.instructor, regexEscape(keyword)),
            regexMatches(lecture.courseTitleEn, regexEscape(keyword)),
            regexMatches(lecture.instructorEn, regexEscape(keyword)),
            exactlyMatches(lecture.courseNumber, keyword),
            exactlyMatches(lecture.lectureNumber, keyword),
        ).reduce(BooleanExpression::or)

    private fun timesCoveredPredicate(times: List<SearchTime>): BooleanExpression =
        hasClassTime()
            .and(
                JPAExpressions
                    .selectOne()
                    .from(classTime)
                    .where(
                        classTime.lectureId.eq(lecture.id),
                        times.map { outsideWindowPredicate(it) }.reduce(BooleanExpression::and),
                    ).exists()
                    .not(),
            )

    private fun outsideWindowPredicate(time: SearchTime): BooleanExpression =
        classTime.day
            .ne(time.day)
            .or(classTime.startMinute.lt(time.startMinute))
            .or(classTime.endMinute.gt(time.endMinute))

    private fun timesExcludedPredicate(timesToExclude: List<SearchTime>): BooleanExpression =
        hasClassTime()
            .and(
                JPAExpressions
                    .selectOne()
                    .from(classTime)
                    .where(
                        classTime.lectureId.eq(lecture.id),
                        timesToExclude.map { overlapsWindowPredicate(it) }.reduce(BooleanExpression::or),
                    ).exists()
                    .not(),
            )

    private fun overlapsWindowPredicate(time: SearchTime): BooleanExpression =
        classTime.day
            .eq(time.day)
            .and(classTime.startMinute.lt(time.endMinute))
            .and(classTime.endMinute.gt(time.startMinute))

    private fun hasClassTime(): BooleanExpression =
        JPAExpressions
            .selectOne()
            .from(classTime)
            .where(classTime.lectureId.eq(lecture.id))
            .exists()

    private fun placePredicate(placeKeyword: String): BooleanExpression {
        val escaped = regexEscape(placeKeyword)
        return JPAExpressions
            .selectOne()
            .from(classTime)
            .where(
                classTime.lectureId.eq(lecture.id),
                // v1은 ^KEY-는 ignore-case, ^KEY$는 대소문자 구분 — ICU (?-i) 인라인 플래그로 재현
                regexMatches(classTime.place, "^$escaped-")
                    .or(regexMatches(classTime.place, "(?-i)^$escaped$")),
            ).exists()
    }

    private fun remarkMatches(pattern: String): BooleanExpression = regexMatches(lecture.remark, pattern)

    private fun regexMatches(
        path: StringExpression,
        pattern: String,
    ): BooleanExpression = Expressions.booleanTemplate("regexp({0}, {1})", path, pattern)

    // v1 isEqualTo는 대소문자를 구분한다 — bineq(MySQL binary 비교)로 재현
    private fun exactlyMatches(
        path: StringExpression,
        value: String,
    ): BooleanExpression = Expressions.booleanTemplate("bineq({0}, {1})", path, value)

    private fun fuzzyPattern(keyword: String): String = keyword.toCharArray().joinToString(".*") { regexEscape(it.toString()) }

    // Kotlin Regex.escape는 \Q..\E(PCRE)를 쓰지만 MySQL ICU는 지원하지 않으므로
    // 백슬래시 이스케이프를 쓴다. 두 엔진 모두 메타문자 리터럴 매칭은 동일하다
    private fun regexEscape(value: String): String =
        value.flatMap { ch -> if (ch in "\\^$.|?*+()[]{}") listOf('\\', ch) else listOf(ch) }.joinToString("")

    private fun String.hasKorean(): Boolean = isNotEmpty() && any { it in '가'..'힣' }
}
