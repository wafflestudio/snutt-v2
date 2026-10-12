package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import org.springframework.data.repository.Repository

interface LegacyPublishedThemeRepository : Repository<PublishedTheme, Long> {
    fun findByAuthorIdAndListedTrueAndSourceThemeIdIsNotNullOrderById(authorId: Long): List<PublishedTheme>

    fun findByAuthorIdAndSourceThemeIdAndListedTrue(
        authorId: Long,
        sourceThemeId: Long,
    ): List<PublishedTheme>
}
