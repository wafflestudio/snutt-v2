package com.wafflestudio.snutt.core.domain.theme.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class ThemeStatus {
    BASIC, // 내장 테마 (DB 행 없음)
    DOWNLOADED, // 마켓에서 받아온 사본
    PUBLISHED, // 공개 중인 테마
    PRIVATE, // 개인 테마
}

// 개인 보관함의 테마 (직접 만든 테마 또는 마켓에서 받아온 사본). 공개 여부는 published_theme로 분리했다.
// builtin 테마(6종)는 DB 행 없이 서비스가 합성한다.
@Entity
@Table(name = "theme")
class TimetableTheme(
    var userId: Long,
    var name: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var colorList: List<ColorSet>,
    // NULL = 직접 만든 테마, SET = 받아온 테마의 원본 참조
    @Column(name = "origin_theme_id")
    var originThemeId: Long? = null,
    @Column(name = "origin_author_id")
    var originAuthorId: Long? = null,
) : ExternalIdEntity()

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
