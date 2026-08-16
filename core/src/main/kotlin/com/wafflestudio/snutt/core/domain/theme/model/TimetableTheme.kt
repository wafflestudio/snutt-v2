package com.wafflestudio.snutt.core.domain.theme.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class ThemeStatus {
    BASIC,
    DOWNLOADED,
    PUBLISHED,
    PRIVATE,
}

@Entity
@Table(name = "theme")
class TimetableTheme(
    val userId: Long?,
    @Column(name = "builtin_type")
    val builtinType: Int? = null,
    var name: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var colors: List<ColorSet>,
    @Column(name = "origin_theme_id")
    var originThemeId: Long? = null,
    @Column(name = "origin_author_id")
    var originAuthorId: Long? = null,
) : ExternalIdEntity() {
    val isBuiltin: Boolean get() = builtinType != null
}

@Entity
@Table(name = "published_theme")
class PublishedTheme(
    @Column(name = "theme_id", nullable = false)
    var themeId: Long,
    @Column(name = "publish_name", nullable = false)
    var publishName: String,
    @Column(name = "author_anonymous", nullable = false)
    var authorAnonymous: Boolean = false,
    @Column(name = "download_count", nullable = false)
    var downloadCount: Long = 0,
) : BaseEntity()
