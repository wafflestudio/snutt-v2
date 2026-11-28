package com.wafflestudio.snutt.v1compat.ev

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.model.YearAndSemester
import com.wafflestudio.snutt.core.common.search.KeywordIntent
import com.wafflestudio.snutt.core.common.search.SearchKeywordClassifier
import com.wafflestudio.snutt.core.domain.evaluation.model.Course
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import org.springframework.data.repository.Repository

data class LegacyCourseSearchCriteria(
    val query: String = "",
    val language: Language = Language.KO,
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
    val avgGradeSatisfaction: Double?,
    val avgTeachingSkill: Double?,
    val avgGains: Double?,
    val avgLifeBalance: Double?,
    val titleEn: String?,
    val instructorEn: String?,
    val departmentEn: String?,
    val academicYearEn: String?,
    val categoryEn: String?,
    val classificationEn: String?,
) {
    fun localized(language: Language): LegacyCourseMetadata =
        copy(
            title = language.select(title, titleEn),
            instructor = language.select(instructor, instructorEn),
            department = language.select(department, departmentEn),
            academicYear = language.select(academicYear, academicYearEn),
            category = language.select(category, categoryEn),
            classification = language.select(classification, classificationEn),
        )
}

interface LegacyCourseRepository :
    Repository<Course, Long>,
    KotlinJdslJpqlExecutor {
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
                    path(Course::department),
                    path(Course::credit),
                    path(Course::academicYear),
                    path(Course::category),
                    path(Course::classification),
                    path(Course::evalCount),
                    path(Course::avgRating),
                    path(Course::avgGradeSatisfaction),
                    path(Course::avgTeachingSkill),
                    path(Course::avgGains),
                    path(Course::avgLifeBalance),
                    path(Course::titleEn),
                    path(Course::instructorEn),
                    path(Course::departmentEn),
                    path(Course::academicYearEn),
                    path(Course::categoryEn),
                    path(Course::classificationEn),
                ).from(entity(Course::class))
                    .where(path(Course::id).`in`(courseIds))
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
                    path(Course::department),
                    path(Course::credit),
                    path(Course::academicYear),
                    path(Course::category),
                    path(Course::classification),
                    path(Course::evalCount),
                    path(Course::avgRating),
                    path(Course::avgGradeSatisfaction),
                    path(Course::avgTeachingSkill),
                    path(Course::avgGains),
                    path(Course::avgLifeBalance),
                    path(Course::titleEn),
                    path(Course::instructorEn),
                    path(Course::departmentEn),
                    path(Course::academicYearEn),
                    path(Course::categoryEn),
                    path(Course::classificationEn),
                ).from(entity(Course::class))
                    .where(and(*predicates(criteria).toTypedArray()))
                    .orderBy(path(Course::evalCount).desc(), path(Course::id).asc())
            }
        }.filterNotNull()
    }

    fun count(criteria: LegacyCourseSearchCriteria): Long =
        findAll {
            jpql {
                select(count(path(Course::id)))
                    .from(entity(Course::class))
                    .where(and(*predicates(criteria).toTypedArray()))
            }
        }.first()!!

    private fun Jpql.predicates(criteria: LegacyCourseSearchCriteria): List<Predicate> {
        val conditions = mutableListOf<Predicate>()
        criteria.department.takeIf { it.isNotEmpty() }?.let { conditions += path(Course::department).`in`(it) }
        if (criteria.yearSemesters.isEmpty()) {
            criteria.credit.takeIf { it.isNotEmpty() }?.let { conditions += path(Course::credit).`in`(it) }
            criteria.classification.takeIf { it.isNotEmpty() }?.let { conditions += path(Course::classification).`in`(it) }
            criteria.academicYear.takeIf { it.isNotEmpty() }?.let { conditions += path(Course::academicYear).`in`(it) }
            criteria.category.takeIf { it.isNotEmpty() }?.let { conditions += path(Course::category).`in`(it) }
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
            when (val intent = SearchKeywordClassifier.classify(word, criteria.language)) {
                KeywordIntent.Empty -> Unit
                KeywordIntent.Major -> alternatives += path(Course::classification).`in`(listOf("전선", "전필"))
                KeywordIntent.Graduate -> alternatives += path(Course::academicYear).`in`(SearchKeywordClassifier.GRADUATE_YEARS)
                KeywordIntent.Undergraduate -> alternatives += path(Course::academicYear).notIn(SearchKeywordClassifier.GRADUATE_YEARS)
                KeywordIntent.PhysicalEducation -> alternatives += path(Course::category).equal("체육")
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
        conditions += path(Course::titleEn).like("%$keyword%")
        conditions += path(Course::instructorEn).like("%$keyword%")
    }

    private fun Jpql.fuzzy(
        conditions: MutableList<Predicate>,
        keyword: String,
    ) {
        val pattern = keyword.fold("%") { acc, character -> "$acc$character%" }
        conditions += path(Course::title).like(pattern)
        conditions += path(Course::category).like(pattern)
        conditions += path(Course::instructor).equal(keyword)
        conditions += path(Course::academicYear).equal(keyword)
        conditions += path(Course::classification).equal(keyword)
        when (keyword.last()) {
            '과', '부' -> conditions += path(Course::department).like(pattern.substring(1, pattern.length - 2))
            '학' -> Unit
            else -> conditions += path(Course::department).like(pattern.substring(1))
        }
    }
}
