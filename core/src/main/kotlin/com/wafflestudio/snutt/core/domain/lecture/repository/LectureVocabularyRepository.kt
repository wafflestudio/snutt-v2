package com.wafflestudio.snutt.core.domain.lecture.repository

import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Instant
import kotlin.reflect.KProperty1

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
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    fun findVocabulary(
        year: Int?,
        semester: Semester?,
        language: Language,
    ): LectureVocabulary {
        val (scopedYear, scopedSemester) = scope(year, semester)
        val perSemester = scopedYear != null
        return LectureVocabulary(
            classification = distinct(scopedYear, scopedSemester, language, Lecture::classification, Lecture::classificationEn),
            department = distinct(scopedYear, scopedSemester, language, Lecture::department, Lecture::departmentEn),
            academicYear = distinct(scopedYear, scopedSemester, language, Lecture::academicYear, Lecture::academicYearEn),
            category = distinct(scopedYear, scopedSemester, language, Lecture::category, Lecture::categoryEn),
            categoryPre2025 =
                distinct(
                    scopedYear,
                    scopedSemester,
                    Language.KO,
                    Lecture::categoryPre2025,
                    Lecture::categoryPre2025,
                ),
            credit = distinctCredits(scopedYear, scopedSemester),
            instructor =
                if (perSemester) {
                    distinct(scopedYear, scopedSemester, language, Lecture::instructor, Lecture::instructorEn)
                } else {
                    emptyList()
                },
            updatedAt = maxUpdatedAt(scopedYear, scopedSemester),
        )
    }

    private fun maxUpdatedAt(
        year: Int?,
        semester: Semester?,
    ): Instant? =
        findAll(offset = null, limit = 1) {
            jpql {
                select(max(path(Lecture::updatedAt)))
                    .from(entity(Lecture::class))
                    .where(
                        and(
                            year?.let { path(Lecture::year).equal(it) },
                            semester?.let { path(Lecture::semester).equal(it) },
                        ),
                    )
            }
        }.firstOrNull()

    private fun distinct(
        year: Int?,
        semester: Semester?,
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
        year: Int?,
        semester: Semester?,
        prop: KProperty1<Lecture, String?>,
    ): List<String> =
        findAll(offset = null, limit = null) {
            jpql {
                selectDistinct(path(prop))
                    .from(entity(Lecture::class))
                    .where(
                        and(
                            year?.let { path(Lecture::year).equal(it) },
                            semester?.let { path(Lecture::semester).equal(it) },
                            path(prop).isNotNull(),
                            path(prop).notEqual(""),
                        ),
                    ).orderBy(path(prop).asc())
            }
        }.filterNotNull()

    private fun distinctCredits(
        year: Int?,
        semester: Semester?,
    ): List<Int> =
        findAll(offset = null, limit = null) {
            jpql {
                selectDistinct(path(Lecture::credit))
                    .from(entity(Lecture::class))
                    .where(
                        and(
                            year?.let { path(Lecture::year).equal(it) },
                            semester?.let { path(Lecture::semester).equal(it) },
                        ),
                    ).orderBy(path(Lecture::credit).asc())
            }
        }.filterNotNull()

    private fun scope(
        year: Int?,
        semester: Semester?,
    ): Pair<Int?, Semester?> =
        if (year != null && semester != null) {
            year to semester
        } else {
            null to null
        }
}
