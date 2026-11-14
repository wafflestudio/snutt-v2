package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface LegacyPublishedThemeRepository : Repository<PublishedTheme, Long> {
    fun findByAuthorIdAndListedTrueAndSourceThemeIdIsNotNullOrderById(authorId: Long): List<PublishedTheme>

    fun findByAuthorIdAndSourceThemeIdAndListedTrue(
        authorId: Long,
        sourceThemeId: Long,
    ): List<PublishedTheme>

    fun findByListedTrueOrderByDownloadCountDescIdDesc(pageable: Pageable): List<PublishedTheme>

    @Query(
        "SELECT p FROM PublishedTheme p WHERE p.listed = true " +
            "AND (p.authorId IN :userIds OR p.id IN (SELECT t.publicationId FROM TimetableTheme t WHERE t.userId IN :userIds)) " +
            "ORDER BY p.downloadCount DESC, p.id DESC",
    )
    fun findFriendsListed(
        userIds: Collection<Long>,
        pageable: Pageable,
    ): List<PublishedTheme>

    @Query(
        "SELECT p FROM PublishedTheme p WHERE p.listed = true " +
            "AND LOWER(p.name) LIKE CONCAT('%', LOWER(:query), '%') " +
            "ORDER BY p.downloadCount DESC, p.id DESC",
    )
    fun searchListed(query: String): List<PublishedTheme>
}
