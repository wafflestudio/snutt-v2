package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TimetableThemeRepository : JpaRepository<TimetableTheme, Long> {
    fun findByExternalIdAndUserId(
        externalId: String,
        userId: Long,
    ): TimetableTheme?

    fun findByExternalId(externalId: String): TimetableTheme?

    fun findAllByExternalIdIn(externalIds: Collection<String>): List<TimetableTheme>

    fun findByUserIdOrderByUpdatedAtDesc(userId: Long): List<TimetableTheme>

    fun findFirstByUserIdOrderByUpdatedAtDesc(userId: Long): TimetableTheme?

    fun existsByOriginThemeIdAndUserId(
        originThemeId: Long,
        userId: Long,
    ): Boolean

    fun existsByUserId(userId: Long): Boolean

    // 기본 테마 지정은 updatedAt 최신화로 표현한다. @UpdateTimestamp는 변경이 없으면 갱신되지 않으므로 명시적으로 쓴다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TimetableTheme t SET t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :themeId")
    fun touchUpdatedAt(themeId: Long)
}

interface PublishedThemeRepository : JpaRepository<PublishedTheme, Long> {
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
