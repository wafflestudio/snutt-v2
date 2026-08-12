package com.wafflestudio.snutt.core.domain.theme.model

import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class ThemeStatus {
    BASIC, // 기본 테마 (DB 행 없음 — v1과 동일하게 커스텀 테마만 저장)
    DOWNLOADED, // 다운로드 받은 테마
    PUBLISHED, // 공유된 테마
    PRIVATE, // 개인 테마
}

// 마켓 정보(origin/publishInfo)는 FK/컬럼으로 평탄화했다 (PLAN.md §2)
@Entity
@Table(name = "theme")
class TimetableTheme(
    var userId: Long,
    var name: String,
    // NULL = builtin 테마 (색상이 클라이언트에 내장)
    @JdbcTypeCode(SqlTypes.JSON)
    var colorList: List<ColorSet>? = null,
    var isCustom: Boolean,
    @Enumerated(EnumType.STRING)
    var status: ThemeStatus,
    @Column(name = "origin_theme_id")
    var originThemeId: Long? = null,
    @Column(name = "origin_author_id")
    var originAuthorId: Long? = null,
    var publishName: String? = null,
    var authorAnonymous: Boolean? = null,
    var downloadCount: Long = 0,
) : ExternalIdEntity()
