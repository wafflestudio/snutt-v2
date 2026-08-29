package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TimetableThemeRepository : JpaRepository<TimetableTheme, Long> {
    fun findByUserIdOrderByUpdatedAtDesc(userId: Long): List<TimetableTheme>

    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): TimetableTheme?

    fun findByUserIdIsNull(): List<TimetableTheme>

    fun existsByOriginThemeIdAndUserId(
        originThemeId: Long,
        userId: Long,
    ): Boolean
}

interface PublishedThemeRepository : JpaRepository<PublishedTheme, Long> {
    @Modifying
    @Query("UPDATE PublishedTheme p SET p.downloadCount = p.downloadCount + 1 WHERE p.id = :id")
    fun incrementDownloadCount(id: Long)

    fun findByThemeId(themeId: Long): PublishedTheme?

    fun findByThemeIdIn(themeIds: Collection<Long>): List<PublishedTheme>

    fun findByPublishNameContainingIgnoreCase(publishName: String): List<PublishedTheme>

    fun findAllByOrderByDownloadCountDesc(pageable: Pageable): List<PublishedTheme>

    @Query(
        """
        SELECT p FROM PublishedTheme p
        WHERE p.themeId IN (SELECT t.id FROM TimetableTheme t WHERE t.userId IN :userIds)
           OR p.themeId IN (
               SELECT d.originThemeId FROM TimetableTheme d
               WHERE d.userId IN :userIds AND d.originThemeId IS NOT NULL
           )
        ORDER BY p.downloadCount DESC
        """,
    )
    fun findFriendsPublished(
        userIds: Collection<Long>,
        pageable: Pageable,
    ): List<PublishedTheme>

    fun existsByThemeId(themeId: Long): Boolean
}
