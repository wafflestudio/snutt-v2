package com.wafflestudio.snutt.core.domain.evaluation.repository

import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.domain.coursebook.service.YearAndSemester
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
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
            classification = distinctStrings(if (english) Lecture::classificationEn else Lecture::classification),
            department = distinctStrings(if (english) Lecture::departmentEn else Lecture::department),
            academicYear = distinctStrings(if (english) Lecture::academicYearEn else Lecture::academicYear),
            credit =
                findAll {
                    jpql {
                        selectDistinct(path(Lecture::credit))
                            .from(entity(Lecture::class))
                            .where(path(Lecture::courseId).isNotNull())
                            .orderBy(path(Lecture::credit).asc())
                    }
                }.filterNotNull(),
            category = distinctStrings(if (english) Lecture::categoryEn else Lecture::category),
            categoryPre2025 = distinctStrings(Lecture::categoryPre2025),
            semesters =
                findAll {
                    jpql {
                        selectNew<YearAndSemester>(path(Lecture::year), path(Lecture::semester))
                            .from(entity(Lecture::class))
                            .where(path(Lecture::courseId).isNotNull())
                            .groupBy(path(Lecture::year), path(Lecture::semester))
                            .orderBy(path(Lecture::year).desc(), path(Lecture::semester).desc())
                    }
                }.filterNotNull(),
            updatedAt =
                findAll {
                    jpql {
                        select(max(path(Lecture::updatedAt))).from(entity(Lecture::class)).where(path(Lecture::courseId).isNotNull())
                    }
                }.firstOrNull(),
        )
    }

    private fun distinctStrings(property: KProperty1<Lecture, String?>): List<String> =
        findAll {
            jpql {
                selectDistinct(path(property))
                    .from(entity(Lecture::class))
                    .where(and(path(Lecture::courseId).isNotNull(), path(property).isNotNull(), path(property).notEqual("")))
                    .orderBy(path(property).asc())
            }
        }.filterNotNull()
}
