package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
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

    fun findByUserIdAndIsCustomTrueOrderByUpdatedAtDesc(userId: Long): List<TimetableTheme>

    // v1은 기본 테마를 "가장 최근 수정한 커스텀 테마"로 본다
    fun findFirstByUserIdAndIsCustomTrueOrderByUpdatedAtDesc(userId: Long): TimetableTheme?

    fun findByStatusOrderByDownloadCountDesc(
        status: ThemeStatus,
        pageable: Pageable,
    ): List<TimetableTheme>

    fun findByStatusAndPublishNameContainingIgnoreCase(
        status: ThemeStatus,
        keyword: String,
    ): List<TimetableTheme>

    fun existsByOriginThemeIdAndUserId(
        originThemeId: Long,
        userId: Long,
    ): Boolean

    fun existsByUserIdAndIsCustomTrue(userId: Long): Boolean

    // 친구가 공유한 테마 + 친구가 받아간 테마의 원본을 합쳐 다운로드순으로 준다
    @Query(
        """
        SELECT t FROM TimetableTheme t
        WHERE t.id IN (
            SELECT p.id FROM TimetableTheme p
            WHERE p.userId IN :userIds AND p.status = com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus.PUBLISHED
        ) OR t.id IN (
            SELECT d.originThemeId FROM TimetableTheme d
            WHERE d.userId IN :userIds AND d.status = com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus.DOWNLOADED
              AND d.originThemeId IS NOT NULL
        )
        ORDER BY t.downloadCount DESC
        """,
    )
    fun findFriendsThemes(
        userIds: Collection<Long>,
        pageable: Pageable,
    ): List<TimetableTheme>

    // 기본 테마 지정은 updatedAt 최신화로 표현한다. @UpdateTimestamp는 변경이 없으면 갱신되지 않으므로 명시적으로 쓴다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TimetableTheme t SET t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :themeId")
    fun touchUpdatedAt(themeId: Long)
}
