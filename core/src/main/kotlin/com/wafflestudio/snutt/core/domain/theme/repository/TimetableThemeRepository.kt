package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TimetableThemeRepository : JpaRepository<TimetableTheme, Long> {
    @Query("SELECT t FROM TimetableTheme t WHERE t.userId = :userId OR t.builtinCode IS NOT NULL ORDER BY t.id")
    fun findAvailableThemes(userId: Long): List<TimetableTheme>

    fun findByUserIdOrderByUpdatedAtDesc(userId: Long): List<TimetableTheme>

    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): TimetableTheme?

    fun findByBuiltinCode(builtinCode: String): TimetableTheme?

    fun existsByUserIdAndPublicationId(
        userId: Long,
        publicationId: Long,
    ): Boolean
}

interface PublishedThemeRepository : JpaRepository<PublishedTheme, Long> {
    @Modifying
    @Query("UPDATE PublishedTheme p SET p.downloadCount = p.downloadCount + 1 WHERE p.id = :id")
    fun incrementDownloadCount(id: Long)

    fun findBySourceThemeIdInAndListedTrue(sourceThemeIds: Collection<Long>): List<PublishedTheme>

    fun findByAuthorIdOrderByIdDesc(authorId: Long): List<PublishedTheme>

    @Query(
        """
        SELECT p FROM PublishedTheme p
        WHERE p.listed = true
          AND (:query IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')))
          AND (:downloadCount IS NULL OR p.downloadCount < :downloadCount
            OR (p.downloadCount = :downloadCount AND p.id < :publicationId))
        ORDER BY p.downloadCount DESC, p.id DESC
        """,
    )
    fun findListed(
        query: String?,
        downloadCount: Long?,
        publicationId: Long?,
        pageable: Pageable,
    ): List<PublishedTheme>

    @Query(
        """
        SELECT p FROM PublishedTheme p
        WHERE p.listed = true AND (
            p.authorId IN :userIds
            OR p.id IN (SELECT t.publicationId FROM TimetableTheme t WHERE t.userId IN :userIds)
        )
          AND (:downloadCount IS NULL OR p.downloadCount < :downloadCount
            OR (p.downloadCount = :downloadCount AND p.id < :publicationId))
        ORDER BY p.downloadCount DESC, p.id DESC
        """,
    )
    fun findFriendsPublished(
        userIds: Collection<Long>,
        downloadCount: Long?,
        publicationId: Long?,
        pageable: Pageable,
    ): List<PublishedTheme>
}
