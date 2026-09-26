package com.wafflestudio.snutt.core.domain.lecture.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.expression.Expressions
import com.linecorp.kotlinjdsl.querymodel.jpql.path.Path
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
    companion object {
        private const val ENGLISH_LECTURE_PATTERN = ".*ⓔ.*"
        private const val MILITARY_LEAVE_PATTERN = ".*ⓜⓞ.*"
        private const val RECOMMENDED_PATTERN = ".*권장과목.*"
        private val ETC_TAG_PATTERNS =
            mapOf(
                "E" to ENGLISH_LECTURE_PATTERN,
                "MO" to MILITARY_LEAVE_PATTERN,
                "R" to RECOMMENDED_PATTERN,
            )

        private fun regexEscape(value: String): String =
            value.flatMap { ch -> if (ch in "\\^$.|?*+()[]{}") listOf('\\', ch) else listOf(ch) }.joinToString("")

        private fun fuzzyPattern(keyword: String): String =
            keyword.codePoints().toArray().joinToString(".*") {
                regexEscape(Character.toString(it))
            }
    }

    override fun search(
        criteria: LectureSearchCriteria,
        cursorLectureId: Long?,
        limit: Int,
        offset: Int?,
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

        return findAll(offset = offset, limit = limit) {
            jpql {
                val predicates = mutableListOf<Predicate>()
                predicates += path(Lecture::year).equal(criteria.year)
                predicates += path(Lecture::semester).equal(criteria.semester)
                cursorLectureId
                    ?.takeIf { criteria.sort == LectureSort.DEFAULT }
                    ?.let { predicates += path(Lecture::id).greaterThan(it) }

                criteria.query?.split(' ')?.forEach { keyword ->
                    when (val intent = SearchKeywordClassifier.classify(keyword, criteria.language)) {
                        KeywordIntent.Empty -> {}
                        KeywordIntent.Major -> predicates += path(Lecture::classification).`in`(listOf("전선", "전필"))
                        KeywordIntent.Graduate -> predicates += path(Lecture::academicYear).`in`(SearchKeywordClassifier.GRADUATE_YEARS)
                        KeywordIntent.Undergraduate ->
                            predicates +=
                                path(Lecture::academicYear).notIn(SearchKeywordClassifier.GRADUATE_YEARS)
                        KeywordIntent.PhysicalEducation -> predicates += path(Lecture::category).equal("체육")
                        KeywordIntent.EnglishLecture -> predicates += regexp(path(Lecture::remark), ENGLISH_LECTURE_PATTERN)
                        KeywordIntent.MilitaryLeave -> predicates += regexp(path(Lecture::remark), MILITARY_LEAVE_PATTERN)
                        KeywordIntent.Recommended -> predicates += regexp(path(Lecture::remark), RECOMMENDED_PATTERN)
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
                                            regexp(path(Lecture::instructorEn), instructorEnPattern(intent.keyword)),
                                            bineq(path(Lecture::courseNumber), intent.keyword),
                                            bineq(path(Lecture::lectureNumber), intent.keyword),
                                        ).toTypedArray(),
                                    )
                            } else {
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

                criteria.etcTags.orEmpty().mapNotNull(ETC_TAG_PATTERNS::get).forEach { pattern ->
                    predicates += regexp(path(Lecture::remark), pattern)
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

    private fun instructorEnPattern(keyword: String): String {
        val separator = "[^A-Za-z0-9가-힣]"
        val letters = keyword.filter { it.isLetterOrDigit() }
        if (letters.isEmpty()) return regexEscape(keyword)
        return letters.toCharArray().joinToString("$separator*", prefix = "(?:^|$separator)") { regexEscape(it.toString()) }
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

    private fun regexp(
        path: Path<String>,
        pattern: String,
    ): Predicate = Predicates.customPredicate("regexp({0}, {1})", listOf(path, Expressions.value(pattern)))

    private fun bineq(
        path: Path<String>,
        value: String,
    ): Predicate = Predicates.customPredicate("bineq({0}, {1})", listOf(path, Expressions.value(value)))

    private data class CursorRating(
        val avgRating: Double?,
        val evalCount: Long,
    )
}
