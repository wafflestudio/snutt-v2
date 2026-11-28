package com.wafflestudio.snutt.core.domain.lecture.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.expression.Expression
import com.linecorp.kotlinjdsl.querymodel.jpql.expression.Expressions
import com.linecorp.kotlinjdsl.querymodel.jpql.path.Path
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicates
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
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
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import kotlin.reflect.KProperty1

data class LectureSearchRow(
    val lecture: Lecture,
    val evalCount: Long,
    val avgRating: Double?,
)

data class LectureVocabulary(
    val classification: List<String>,
    val department: List<String>,
    val academicYear: List<String>,
    val category: List<String>,
    val categoryPre2025: List<String>,
    val credit: List<Int>,
    val instructor: List<String>,
    val updatedAt: Instant?,
)

private data class LectureSearchCursorRating(
    val avgRating: Double?,
    val evalCount: Long,
)

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

private const val NON_WORD = "[^A-Za-z0-9]"
private const val MAX_EDGE_WORDS = 3
private const val MIN_INSTRUCTOR_LETTERS = 2
private const val MAX_SEQUENCE_CHARS = 40
private val COURSE_NUMBER = Regex("""[A-Za-z]?\d+[A-Za-z]?\.[A-Za-z]?\d+[A-Za-z]?""")
private val LECTURE_NUMBER = Regex("""\d{3}""")

private fun englishToken(keyword: String): String = keyword.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

private fun titlePattern(tokens: List<String>): String =
    tokens.joinToString(".*$NON_WORD", prefix = "(?:^|$NON_WORD)") { it.toList().joinToString("(?:.*$NON_WORD)?") }

private fun instructorPattern(token: String): String = token.toList().joinToString("$NON_WORD*", prefix = "(?:^|$NON_WORD)")

private fun wordPattern(keyword: String): String = "(?:^|$NON_WORD)${regexEscape(keyword)}(?:$|$NON_WORD)"

interface LectureRepository :
    JpaRepository<Lecture, Long>,
    KotlinJdslJpqlExecutor {
    fun findByCourseIdOrderByYearDescSemesterDesc(courseId: Long): List<Lecture>

    /** 시간표에 담기 전 강의 행을 공유 락으로 읽는다. 수강편람 동기화가 이 강의를 고치거나 지우는 중이면 그 커밋을 기다린다. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT l FROM Lecture l WHERE l.id = :id")
    fun findForShareById(id: Long): Lecture?

    /** 수강편람 동기화가 고칠 강의를 id 순으로 잠근다. */
    @Query(value = "SELECT id FROM lecture WHERE id IN (:ids) ORDER BY id FOR UPDATE", nativeQuery = true)
    fun lockAllByIdIn(ids: Collection<Long>): List<Long>

    /** 지울 강의를 id 순으로 잠그되, 다른 트랜잭션이 잡고 있는 행(지금 시간표에 담기는 중인 강의)은 건너뛴다. */
    @Query(value = "SELECT id FROM lecture WHERE id IN (:ids) ORDER BY id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    fun lockAllByIdInSkipLocked(ids: Collection<Long>): List<Long>

    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): List<Lecture>

    fun search(
        criteria: LectureSearchCriteria,
        cursorLectureId: Long?,
        limit: Int,
        offset: Int? = null,
    ): List<LectureSearchRow> {
        val cursorRating =
            if (cursorLectureId != null && criteria.sort != LectureSort.DEFAULT) {
                findAll(offset = null, limit = 1) {
                    jpql {
                        selectNew<LectureSearchCursorRating>(
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

                val englishKeywords = mutableListOf<String>()
                criteria.query?.split(' ')?.forEach { keyword ->
                    when (val intent = SearchKeywordClassifier.classify(keyword, Language.KO)) {
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
                            val place =
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
                            predicates += if (criteria.language == Language.EN) or(place, englishTitleWord(keyword)) else place
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
                                englishKeywords += intent.keyword
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
                predicates += englishQueryPredicates(englishKeywords)

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

    private fun Jpql.englishQueryPredicates(keywords: List<String>): List<Predicate> {
        val predicates = mutableListOf<Predicate>()
        val rawTokens = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        keywords.forEach { keyword ->
            val token = englishToken(keyword)
            when {
                COURSE_NUMBER.matches(keyword) -> predicates += bineq(path(Lecture::courseNumber), keyword.uppercase())
                LECTURE_NUMBER.matches(keyword) -> predicates += or(bineq(path(Lecture::lectureNumber), keyword), englishTitleWord(keyword))
                token.isEmpty() -> predicates += englishPlainPredicate(keyword)
                else -> {
                    rawTokens += keyword
                    tokens += token
                }
            }
        }
        when {
            tokens.isEmpty() -> Unit
            tokens.sumOf { it.length } > MAX_SEQUENCE_CHARS -> rawTokens.forEach { predicates += englishPlainPredicate(it) }
            else -> predicates += englishSequencePredicate(tokens)
        }
        return predicates
    }

    private fun Jpql.englishSequencePredicate(tokens: List<String>): Predicate {
        val title = englishTitle()
        val instructor = coalesce(path(Lecture::instructorEn), path(Lecture::instructor))
        val m = tokens.size

        fun titleOf(
            from: Int,
            to: Int,
        ): Predicate = regexp(title, titlePattern(tokens.subList(from, to)))

        fun instructorOf(index: Int): Predicate? =
            tokens[index].takeIf { it.length >= MIN_INSTRUCTOR_LETTERS }?.let { regexp(instructor, instructorPattern(it)) }

        fun prefix(k: Int): Predicate? {
            if (k > minOf(MAX_EDGE_WORDS, m)) return null
            val matched = instructorOf(k - 1) ?: return null
            if (k == m) return matched
            return and(matched, or(*listOfNotNull(titleOf(k, m), prefix(k + 1)).toTypedArray()))
        }

        fun suffix(k: Int): Predicate? {
            if (k > minOf(MAX_EDGE_WORDS, m - 1)) return null
            val matched = instructorOf(m - k) ?: return null
            return and(matched, or(*listOfNotNull(titleOf(0, m - k), suffix(k + 1)).toTypedArray()))
        }

        return or(*listOfNotNull(titleOf(0, m), prefix(1), suffix(1)).toTypedArray())
    }

    private fun Jpql.englishPlainPredicate(keyword: String): Predicate {
        val token = englishToken(keyword)
        return or(
            *listOfNotNull(
                regexp(path(Lecture::courseTitle), regexEscape(keyword)),
                regexp(path(Lecture::courseTitleEn), regexEscape(keyword)),
                regexp(path(Lecture::instructor), regexEscape(keyword)),
                token.takeIf { it.isNotEmpty() }?.let { regexp(path(Lecture::instructorEn), instructorPattern(it)) },
            ).toTypedArray(),
        )
    }

    private fun Jpql.englishTitle(): Expression<String> = coalesce(path(Lecture::courseTitleEn), path(Lecture::courseTitle))

    private fun Jpql.englishTitleWord(keyword: String): Predicate = regexp(englishTitle(), wordPattern(keyword))

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
        expression: Expression<String>,
        pattern: String,
    ): Predicate = Predicates.customPredicate("regexp({0}, {1})", listOf(expression, Expressions.value(pattern)))

    private fun bineq(
        path: Path<String>,
        value: String,
    ): Predicate = Predicates.customPredicate("bineq({0}, {1})", listOf(path, Expressions.value(value)))

    fun findVocabulary(
        year: Int,
        semester: Semester,
        language: Language,
    ): LectureVocabulary =
        LectureVocabulary(
            classification = distinct(year, semester, language, Lecture::classification, Lecture::classificationEn),
            department = distinct(year, semester, language, Lecture::department, Lecture::departmentEn),
            academicYear = distinct(year, semester, language, Lecture::academicYear, Lecture::academicYearEn),
            category = distinct(year, semester, language, Lecture::category, Lecture::categoryEn),
            categoryPre2025 = distinctStrings(year, semester, Lecture::categoryPre2025),
            credit = distinctCredits(year, semester),
            instructor = distinct(year, semester, language, Lecture::instructor, Lecture::instructorEn),
            updatedAt = maxUpdatedAt(year, semester),
        )

    private fun maxUpdatedAt(
        year: Int,
        semester: Semester,
    ): Instant? =
        findAll(offset = null, limit = 1) {
            jpql {
                select(max(path(Lecture::updatedAt)))
                    .from(entity(Lecture::class))
                    .where(
                        and(
                            path(Lecture::year).equal(year),
                            path(Lecture::semester).equal(semester),
                        ),
                    )
            }
        }.firstOrNull()

    private fun distinct(
        year: Int,
        semester: Semester,
        language: Language,
        ko: KProperty1<Lecture, String?>,
        en: KProperty1<Lecture, String?>,
    ): List<String> {
        if (language == Language.EN) {
            val english = distinctStrings(year, semester, en)
            if (english.isNotEmpty()) return english
        }
        return distinctStrings(year, semester, ko)
    }

    private fun distinctStrings(
        year: Int,
        semester: Semester,
        prop: KProperty1<Lecture, String?>,
    ): List<String> =
        findAll(offset = null, limit = null) {
            jpql {
                selectDistinct(path(prop))
                    .from(entity(Lecture::class))
                    .where(
                        and(
                            path(Lecture::year).equal(year),
                            path(Lecture::semester).equal(semester),
                            path(prop).isNotNull(),
                        ),
                    ).orderBy(path(prop).asc())
            }
        }.filterNotNull()

    private fun distinctCredits(
        year: Int,
        semester: Semester,
    ): List<Int> =
        findAll(offset = null, limit = null) {
            jpql {
                selectDistinct(path(Lecture::credit))
                    .from(entity(Lecture::class))
                    .where(
                        and(
                            path(Lecture::year).equal(year),
                            path(Lecture::semester).equal(semester),
                        ),
                    ).orderBy(path(Lecture::credit).asc())
            }
        }.filterNotNull()
}
