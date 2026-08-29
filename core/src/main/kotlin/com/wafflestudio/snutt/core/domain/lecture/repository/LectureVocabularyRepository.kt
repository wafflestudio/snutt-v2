package com.wafflestudio.snutt.core.domain.lecture.repository

import com.querydsl.core.types.dsl.BooleanExpression
import com.querydsl.core.types.dsl.NumberPath
import com.querydsl.core.types.dsl.StringPath
import com.querydsl.jpa.impl.JPAQueryFactory
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.QLecture
import org.springframework.stereotype.Repository
import java.time.Instant

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

@Repository
class LectureVocabularyRepository(
    private val queryFactory: JPAQueryFactory,
) {
    private val lecture = QLecture.lecture

    fun findVocabulary(
        year: Int?,
        semester: Semester?,
        language: Language,
    ): LectureVocabulary {
        val scope = scope(year, semester)
        val perSemester = scope != null
        return LectureVocabulary(
            classification = distinct(scope, language, lecture.classification, lecture.classificationEn),
            department = distinct(scope, language, lecture.department, lecture.departmentEn),
            academicYear = distinct(scope, language, lecture.academicYear, lecture.academicYearEn),
            category = distinct(scope, language, lecture.category, lecture.categoryEn),
            categoryPre2025 = distinct(scope, Language.KO, lecture.categoryPre2025, lecture.categoryPre2025),
            credit = distinct(scope, lecture.credit),
            instructor = if (perSemester) distinct(scope, language, lecture.instructor, lecture.instructorEn) else emptyList(),
            updatedAt =
                queryFactory
                    .select(lecture.updatedAt.max())
                    .from(lecture)
                    .where(scope)
                    .fetchOne(),
        )
    }

    private fun scope(
        year: Int?,
        semester: Semester?,
    ): BooleanExpression? = if (year != null && semester != null) lecture.year.eq(year).and(lecture.semester.eq(semester)) else null

    private fun distinct(
        scope: BooleanExpression?,
        language: Language,
        ko: StringPath,
        en: StringPath,
    ): List<String> {
        if (language == Language.EN) {
            val english = distinct(scope, en)
            if (english.isNotEmpty()) return english
        }
        return distinct(scope, ko)
    }

    private fun distinct(
        scope: BooleanExpression?,
        path: StringPath,
    ): List<String> =
        queryFactory
            .select(path)
            .distinct()
            .from(lecture)
            .where(scope, path.isNotNull, path.ne(""))
            .orderBy(path.asc())
            .fetch()

    private fun distinct(
        scope: BooleanExpression?,
        path: NumberPath<Int>,
    ): List<Int> =
        queryFactory
            .select(path)
            .distinct()
            .from(lecture)
            .where(scope, path.isNotNull)
            .orderBy(path.asc())
            .fetch()
}
