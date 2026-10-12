package com.wafflestudio.snutt.core.domain.theme.repository

import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme

data class PublishedThemeCursor(
    val downloadCount: Long,
    val publicationId: Long,
)

interface PublishedThemeSearchRepository {
    fun findListed(
        query: String?,
        cursor: PublishedThemeCursor?,
        limit: Int,
    ): List<PublishedTheme>

    fun findListedAuthoredOrDownloadedBy(
        userIds: Collection<Long>,
        cursor: PublishedThemeCursor?,
        limit: Int,
    ): List<PublishedTheme>
}
