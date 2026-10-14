package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TimetableThemeRepository : JpaRepository<TimetableTheme, Long> {
    fun findByUserIdOrderById(userId: Long): List<TimetableTheme>

    fun findByBuiltinCodeIsNotNullOrderById(): List<TimetableTheme>

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

    fun findByAuthorIdOrderByIdDesc(authorId: Long): List<PublishedTheme>

    @Query(
        "SELECT p FROM PublishedTheme p WHERE p.listed = true " +
            "AND (:query IS NULL OR LOWER(p.name) LIKE CONCAT('%', LOWER(:query), '%')) " +
            "AND (:cursorDownloadCount IS NULL OR p.downloadCount < :cursorDownloadCount " +
            "OR (p.downloadCount = :cursorDownloadCount AND p.id < :cursorId)) " +
            "ORDER BY p.downloadCount DESC, p.id DESC",
    )
    fun findListed(
        query: String?,
        cursorDownloadCount: Long?,
        cursorId: Long?,
        limit: Limit,
    ): List<PublishedTheme>

    @Query(
        "SELECT p FROM PublishedTheme p WHERE p.listed = true " +
            "AND (p.authorId IN :userIds OR p.id IN (SELECT t.publicationId FROM TimetableTheme t WHERE t.userId IN :userIds)) " +
            "AND (:cursorDownloadCount IS NULL OR p.downloadCount < :cursorDownloadCount " +
            "OR (p.downloadCount = :cursorDownloadCount AND p.id < :cursorId)) " +
            "ORDER BY p.downloadCount DESC, p.id DESC",
    )
    fun findListedAuthoredOrDownloadedBy(
        userIds: Collection<Long>,
        cursorDownloadCount: Long?,
        cursorId: Long?,
        limit: Limit,
    ): List<PublishedTheme>
}
