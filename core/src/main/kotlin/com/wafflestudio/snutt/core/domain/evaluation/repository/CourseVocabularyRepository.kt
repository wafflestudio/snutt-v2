package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester
import com.wafflestudio.snutt.core.domain.evaluation.model.CourseSemester
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Instant
import kotlin.reflect.KProperty1

data class CourseVocabulary(
    val classification: List<String>,
    val department: List<String>,
    val academicYear: List<String>,
    val credit: List<Int>,
    val category: List<String>,
    val categoryPre2025: List<String>,
    val semesters: List<YearAndSemester>,
    val updatedAt: Instant?,
)

@Repository
class CourseVocabularyRepository(
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    fun getVocabulary(language: Language): CourseVocabulary {
        val english = language == Language.EN
        return CourseVocabulary(
            classification = distinctStrings(if (english) CourseSemester::classificationEn else CourseSemester::classification),
            department = distinctStrings(if (english) CourseSemester::departmentEn else CourseSemester::department),
            academicYear = distinctStrings(if (english) CourseSemester::academicYearEn else CourseSemester::academicYear),
            credit =
                findAll {
                    jpql {
                        selectDistinct(path(CourseSemester::credit))
                            .from(entity(CourseSemester::class))
                            .orderBy(path(CourseSemester::credit).asc())
                    }
                }.filterNotNull(),
            category = distinctStrings(if (english) CourseSemester::categoryEn else CourseSemester::category),
            categoryPre2025 = distinctStrings(CourseSemester::categoryPre2025),
            semesters =
                findAll {
                    jpql {
                        selectNew<YearAndSemester>(path(CourseSemester::year), path(CourseSemester::semester))
                            .from(entity(CourseSemester::class))
                            .groupBy(path(CourseSemester::year), path(CourseSemester::semester))
                            .orderBy(path(CourseSemester::year).desc(), path(CourseSemester::semester).desc())
                    }
                }.filterNotNull(),
            updatedAt =
                findAll {
                    jpql {
                        select(max(path(CourseSemester::updatedAt))).from(entity(CourseSemester::class))
                    }
                }.firstOrNull(),
        )
    }

    private fun distinctStrings(property: KProperty1<CourseSemester, String?>): List<String> =
        findAll {
            jpql {
                selectDistinct(path(property))
                    .from(entity(CourseSemester::class))
                    .where(and(path(property).isNotNull(), path(property).notEqual("")))
                    .orderBy(path(property).asc())
            }
        }.filterNotNull()
}
