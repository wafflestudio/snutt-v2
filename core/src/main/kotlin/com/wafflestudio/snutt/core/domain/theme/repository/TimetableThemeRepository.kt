package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.TimetableTheme
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TimetableThemeRepository : JpaRepository<TimetableTheme, Long> {
    fun findByUserIdOrderById(userId: Long): List<TimetableTheme>

    fun findByBuiltinCodeIsNotNullOrderById(): List<TimetableTheme>

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

interface PublishedThemeRepository :
    JpaRepository<PublishedTheme, Long>,
    PublishedThemeSearchRepository {
    @Modifying
    @Query("UPDATE PublishedTheme p SET p.downloadCount = p.downloadCount + 1 WHERE p.id = :id")
    fun incrementDownloadCount(id: Long)

    fun findBySourceThemeIdInAndListedTrue(sourceThemeIds: Collection<Long>): List<PublishedTheme>

    fun findByAuthorIdOrderByIdDesc(authorId: Long): List<PublishedTheme>
}
