package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

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
}
