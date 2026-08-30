package com.wafflestudio.snutt.core.domain.lecture.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.expression.Expressions
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicates
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.search.KeywordIntent
import com.wafflestudio.snutt.core.common.search.SearchKeywordClassifier
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSearchCriteria
import com.wafflestudio.snutt.core.domain.lecture.dto.LectureSort
import com.wafflestudio.snutt.core.domain.lecture.dto.SearchTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureClassTime
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class LectureSearchRepositoryImpl(
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : LectureSearchRepository,
    KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    private val classifier = SearchKeywordClassifier(placeRegex, buildingRegex)

    companion object {
        private val placeRegex = """^(?:|#|\*)\d+(?:-\d+|-[a-zA-Z])?-[a-zA-Z]?\d+[a-zA-Z]?(?:-\d+)?$""".toRegex()
        private val buildingRegex = """^(?:|#|\*)\d+(?:-\d+)?동$""".toRegex()
        private val GRADUATE_YEARS = listOf("석사", "박사", "석박사통합")

        // Kotlin Regex.escape는 \Q..\E(PCRE)를 쓰지만 MySQL ICU는 지원하지 않으므로
        // 백슬래시 이스케이프를 쓴다. 두 엔진 모두 메타문자 리터럴 매칭은 동일하다
        private fun regexEscape(value: String): String =
            value.flatMap { ch -> if (ch in "\\^$.|?*+()[]{}") listOf('\\', ch) else listOf(ch) }.joinToString("")

        private fun fuzzyPattern(keyword: String): String = keyword.toCharArray().joinToString(".*") { regexEscape(it.toString()) }
    }

    override fun search(
        criteria: LectureSearchCriteria,
        cursorLectureId: Long?,
        limit: Int,
    ): List<LectureSearchRow> {
        val cursorRating =
            if (cursorLectureId != null && criteria.sort != LectureSort.DEFAULT) {
                findAll(offset = null, limit = 1) {
                    jpql {
                        selectNew<CursorRating>(
                            path(Course::avgRating),
                            coalesce(path(Course::evalCount), 0L),
                        ).from(
                            entity(Lecture::class),
                            leftJoin(Course::class).on(
                                path(Lecture::courseId).equal(path(Course::id)),
                            ),
                        ).where(
                            path(Lecture::id).equal(cursorLectureId),
                        )
                    }
                }.firstOrNull() ?: throw SnuttException(ErrorType.INVALID_CURSOR)
            } else {
                null
            }

        return findAll(offset = null, limit = limit) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Lecture::year).equal(criteria.year)
                predicates += path(Lecture::semester).equal(criteria.semester)
                cursorLectureId
                    ?.takeIf { criteria.sort == LectureSort.DEFAULT }
                    ?.let { predicates += path(Lecture::id).greaterThan(it) }

                criteria.query?.split(' ')?.forEach { keyword ->
                    when (val intent = classifier.classify(keyword, criteria.language)) {
                        KeywordIntent.Empty -> {}
                        KeywordIntent.Major -> predicates += path(Lecture::classification).`in`(listOf("전선", "전필"))
                        KeywordIntent.Graduate -> predicates += path(Lecture::academicYear).`in`(GRADUATE_YEARS)
                        KeywordIntent.Undergraduate -> predicates += path(Lecture::academicYear).notIn(GRADUATE_YEARS)
                        KeywordIntent.PhysicalEducation -> predicates += path(Lecture::category).equal("체육")
                        KeywordIntent.EnglishLecture -> predicates += regexp(path(Lecture::remark), ".*ⓔ.*")
                        KeywordIntent.MilitaryLeave -> predicates += regexp(path(Lecture::remark), ".*ⓜⓞ.*")
                        KeywordIntent.Recommended -> predicates += regexp(path(Lecture::remark), ".*권장과목.*")
                        is KeywordIntent.Place -> {
                            val escaped = regexEscape(intent.keyword)
                            predicates +=
                                exists(
                                    jpql {
                                        select(value(1))
                                            .from(entity(LectureClassTime::class))
                                            .where(
                                                and(
                                                    path(LectureClassTime::lectureId).equal(path(Lecture::id)),
                                                    or(
                                                        regexp(path(LectureClassTime::place), "^$escaped-"),
                                                        regexp(path(LectureClassTime::place), "(?-i)^$escaped$"),
                                                    ),
                                                ),
                                            )
                                    }.asSubquery(),
                                )
                        }
                        is KeywordIntent.Fuzzy -> {
                            val fuzzyKeyword = fuzzyPattern(intent.keyword)
                            val departmentPredicate =
                                when (intent.keyword.last()) {
                                    '과', '부' -> regexp(path(Lecture::department), "^${fuzzyPattern(intent.keyword.dropLast(1))}")
                                    '학' -> null
                                    else -> regexp(path(Lecture::department), "^$fuzzyKeyword")
                                }
                            predicates +=
                                or(
                                    *listOfNotNull(
                                        regexp(path(Lecture::courseTitle), fuzzyKeyword),
                                        regexp(path(Lecture::category), fuzzyKeyword),
                                        bineq(path(Lecture::instructor), intent.keyword),
                                        bineq(path(Lecture::academicYear), intent.keyword),
                                        bineq(path(Lecture::classification), intent.keyword),
                                        departmentPredicate,
                                    ).toTypedArray(),
                                )
                        }
                        is KeywordIntent.Plain ->
                            if (criteria.language == Language.EN) {
                                predicates +=
                                    or(
                                        *listOfNotNull(
                                            regexp(path(Lecture::courseTitle), regexEscape(intent.keyword)),
                                            regexp(path(Lecture::courseTitleEn), regexEscape(intent.keyword)),
                                            regexp(path(Lecture::instructor), regexEscape(intent.keyword)),
                                            regexp(path(Lecture::instructorEn), regexEscape(intent.keyword)),
                                            bineq(path(Lecture::courseNumber), intent.keyword),
                                            bineq(path(Lecture::lectureNumber), intent.keyword),
                                        ).toTypedArray(),
                                    )
                            } else {
                                // 구버전 KO 분기: 한글 미포함 키워드는 ko 필드만 대상으로 한다
                                predicates +=
                                    or(
                                        *listOfNotNull(
                                            regexp(path(Lecture::courseTitle), regexEscape(intent.keyword)),
                                            regexp(path(Lecture::instructor), regexEscape(intent.keyword)),
                                            bineq(path(Lecture::courseNumber), intent.keyword),
                                            bineq(path(Lecture::lectureNumber), intent.keyword),
                                        ).toTypedArray(),
                                    )
                            }
                    }
                }

                criteria.classification?.takeIf { it.isNotEmpty() }?.let {
                    predicates +=
                        or(
                            path(Lecture::classification).`in`(it),
                            path(Lecture::classificationEn).`in`(it),
                        )
                }
                criteria.credit?.takeIf { it.isNotEmpty() }?.let { predicates += path(Lecture::credit).`in`(it) }
                criteria.courseNumber?.takeIf { it.isNotEmpty() }?.let { predicates += path(Lecture::courseNumber).`in`(it) }
                criteria.academicYear?.takeIf { it.isNotEmpty() }?.let {
                    predicates +=
                        or(
                            path(Lecture::academicYear).`in`(it),
                            path(Lecture::academicYearEn).`in`(it),
                        )
                }
                criteria.department?.takeIf { it.isNotEmpty() }?.let {
                    predicates +=
                        or(
                            path(Lecture::department).`in`(it),
                            path(Lecture::departmentEn).`in`(it),
                        )
                }

                val category = criteria.category.orEmpty().filter(String::isNotEmpty)
                val categoryPre2025 = criteria.categoryPre2025.orEmpty().filter(String::isNotEmpty)
                if (category.isNotEmpty() || categoryPre2025.isNotEmpty()) {
                    predicates +=
                        or(
                            *listOfNotNull(
                                category.takeIf { it.isNotEmpty() }?.let {
                                    or(
                                        path(Lecture::category).`in`(it),
                                        path(Lecture::categoryEn).`in`(it),
                                    )
                                },
                                categoryPre2025.takeIf { it.isNotEmpty() }?.let { path(Lecture::categoryPre2025).`in`(it) },
                            ).toTypedArray(),
                        )
                }

                criteria.times?.takeIf { it.isNotEmpty() }?.let { times ->
                    predicates +=
                        and(
                            hasClassTime(),
                            not(
                                exists(
                                    jpql {
                                        select(value(1))
                                            .from(entity(LectureClassTime::class))
                                            .where(
                                                and(
                                                    path(LectureClassTime::lectureId).equal(path(Lecture::id)),
                                                    and(*times.map { outsideWindow(it) }.toTypedArray()),
                                                ),
                                            )
                                    }.asSubquery(),
                                ),
                            ),
                        )
                }
                criteria.timesToExclude?.takeIf { it.isNotEmpty() }?.let { times ->
                    predicates +=
                        and(
                            hasClassTime(),
                            not(
                                exists(
                                    jpql {
                                        select(value(1))
                                            .from(entity(LectureClassTime::class))
                                            .where(
                                                and(
                                                    path(LectureClassTime::lectureId).equal(path(Lecture::id)),
                                                    or(*times.map { overlapsWindow(it) }.toTypedArray()),
                                                ),
                                            )
                                    }.asSubquery(),
                                ),
                            ),
                        )
                }

                criteria.etcTags.orEmpty().forEach { etcTag ->
                    when (etcTag) {
                        "E" -> predicates += regexp(path(Lecture::remark), ".*ⓔ.*")
                        "MO" -> predicates += regexp(path(Lecture::remark), ".*ⓜⓞ.*")
                        "R" -> predicates += regexp(path(Lecture::remark), ".*권장과목.*")
                    }
                }

                if (cursorRating != null) {
                    predicates +=
                        when (criteria.sort) {
                            LectureSort.RATING_DESC ->
                                if (cursorRating.avgRating == null) {
                                    and(
                                        path(Course::avgRating).isNull(),
                                        path(Lecture::id).greaterThan(cursorLectureId!!),
                                    )
                                } else {
                                    or(
                                        path(Course::avgRating).lessThan(cursorRating.avgRating),
                                        path(Course::avgRating).isNull(),
                                        and(
                                            path(Course::avgRating).equal(cursorRating.avgRating),
                                            path(Lecture::id).greaterThan(cursorLectureId!!),
                                        ),
                                    )
                                }
                            LectureSort.COUNT_DESC ->
                                or(
                                    coalesce(path(Course::evalCount), 0L).lessThan(cursorRating.evalCount),
                                    and(
                                        coalesce(path(Course::evalCount), 0L).equal(cursorRating.evalCount),
                                        path(Lecture::id).greaterThan(cursorLectureId!!),
                                    ),
                                )
                            LectureSort.DEFAULT -> throw IllegalStateException()
                        }
                }

                val order =
                    when (criteria.sort) {
                        LectureSort.DEFAULT -> listOf(path(Lecture::id).asc())
                        LectureSort.RATING_DESC ->
                            listOf(path(Course::avgRating).desc(), path(Lecture::id).asc())
                        LectureSort.COUNT_DESC ->
                            listOf(coalesce(path(Course::evalCount), 0L).desc(), path(Lecture::id).asc())
                    }

                selectNew<LectureSearchRow>(
                    entity(Lecture::class),
                    coalesce(path(Course::evalCount), 0L),
                    path(Course::avgRating),
                ).from(
                    entity(Lecture::class),
                    leftJoin(Course::class).on(path(Lecture::courseId).equal(path(Course::id))),
                ).where(
                    and(*predicates.toTypedArray()),
                ).orderBy(
                    *order.toTypedArray(),
                )
            }
        }.filterNotNull()
    }

    private fun Jpql.hasClassTime(): Predicate =
        exists(
            jpql {
                select(value(1))
                    .from(entity(LectureClassTime::class))
                    .where(path(LectureClassTime::lectureId).equal(path(Lecture::id)))
            }.asSubquery(),
        )

    private fun Jpql.outsideWindow(it: SearchTime): Predicate =
        or(
            path(LectureClassTime::day).notEqual(it.day),
            path(LectureClassTime::startMinute).lessThan(it.startMinute),
            path(LectureClassTime::endMinute).greaterThan(it.endMinute),
        )

    private fun Jpql.overlapsWindow(it: SearchTime): Predicate =
        and(
            path(LectureClassTime::day).equal(it.day),
            path(LectureClassTime::startMinute).lessThan(it.endMinute),
            path(LectureClassTime::endMinute).greaterThan(it.startMinute),
        )

    // Hibernate는 FUNCTION('regexp', ...)를 비타입으로 해석하므로
    // RegexpFunctionContributor에 등록된 함수 이름으로 직접 렌더링해야 boolean 타입이 추론된다
    private fun regexp(
        path: com.linecorp.kotlinjdsl.querymodel.jpql.path.Path<String>,
        pattern: String,
    ): Predicate = Predicates.customPredicate("regexp({0}, {1})", listOf(path, Expressions.value(pattern)))

    private fun bineq(
        path: com.linecorp.kotlinjdsl.querymodel.jpql.path.Path<String>,
        value: String,
    ): Predicate = Predicates.customPredicate("bineq({0}, {1})", listOf(path, Expressions.value(value)))

    private data class CursorRating(
        val avgRating: Double?,
        val evalCount: Long,
    )
}
