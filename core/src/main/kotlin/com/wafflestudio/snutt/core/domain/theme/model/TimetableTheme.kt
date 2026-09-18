package com.wafflestudio.snutt.core.domain.theme.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class ThemeKind {
    BUILTIN,
    CUSTOM,
    DOWNLOADED,
}

@Entity
class TimetableTheme(
    val userId: Long?,
    val builtinCode: String? = null,
    var name: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    var colors: List<ColorSet>? = null,
    val publicationId: Long? = null,
) : BaseEntity() {
    val kind: ThemeKind
        get() =
            when {
                builtinCode != null -> ThemeKind.BUILTIN
                publicationId != null -> ThemeKind.DOWNLOADED
                else -> ThemeKind.CUSTOM
            }
}

@Entity
class PublishedTheme(
    val authorId: Long?,
    val sourceThemeId: Long?,
    val name: String,
    @JdbcTypeCode(SqlTypes.JSON)
    val colors: List<ColorSet>,
    val authorAnonymous: Boolean = false,
    var listed: Boolean = true,
    var downloadCount: Long = 0,
) : BaseEntity()
