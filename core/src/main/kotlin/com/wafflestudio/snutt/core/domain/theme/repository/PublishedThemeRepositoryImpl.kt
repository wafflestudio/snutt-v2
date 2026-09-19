package com.wafflestudio.snutt.core.domain.theme.repository

import com.linecorp.kotlinjdsl.dsl.jpql.Jpql
import com.linecorp.kotlinjdsl.dsl.jpql.jpql
import com.linecorp.kotlinjdsl.querymodel.jpql.predicate.Predicate
import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutorImpl
import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class PublishedThemeRepositoryImpl(
    entityManager: EntityManager,
    context: JpqlRenderContext,
) : PublishedThemeSearchRepository,
    KotlinJdslJpqlExecutor by KotlinJdslJpqlExecutorImpl(entityManager, context, null) {
    override fun findListed(
        query: String?,
        cursor: PublishedThemeCursor?,
        limit: Int,
    ): List<PublishedTheme> =
        findListedBy(cursor, limit) {
            query?.let { lower(path(PublishedTheme::name)).like("%${it.lowercase()}%") }
        }

    override fun findListedByAuthorIds(
        authorIds: Collection<Long>,
        cursor: PublishedThemeCursor?,
        limit: Int,
    ): List<PublishedTheme> =
        findListedBy(cursor, limit) {
            path(PublishedTheme::authorId).`in`(authorIds)
        }

    override fun findListedDownloadedByUsers(
        userIds: Collection<Long>,
        cursor: PublishedThemeCursor?,
        limit: Int,
    ): List<PublishedTheme> =
        findListedBy(cursor, limit) {
            path(PublishedTheme::id).`in`(
                jpql {
                    select(path(TimetableTheme::publicationId))
                        .from(entity(TimetableTheme::class))
                        .where(path(TimetableTheme::userId).`in`(userIds))
                }.asSubquery(),
            )
        }

    private fun findListedBy(
        cursor: PublishedThemeCursor?,
        limit: Int,
        condition: Jpql.() -> Predicate?,
    ): List<PublishedTheme> =
        findAll(limit = limit) {
            jpql {
                select(entity(PublishedTheme::class))
                    .from(entity(PublishedTheme::class))
                    .where(
                        and(
                            path(PublishedTheme::listed).equal(true),
                            condition(),
                            beforeCursor(cursor),
                        ),
                    ).orderBy(
                        path(PublishedTheme::downloadCount).desc(),
                        path(PublishedTheme::id).desc(),
                    )
            }
        }.filterNotNull()

    private fun Jpql.beforeCursor(cursor: PublishedThemeCursor?): Predicate? =
        cursor?.let {
            or(
                path(PublishedTheme::downloadCount).lessThan(it.downloadCount),
                and(
                    path(PublishedTheme::downloadCount).equal(it.downloadCount),
                    path(PublishedTheme::id).lessThan(it.publicationId),
                ),
            )
        }
}
