package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "timetable")
class Timetable(
    var userId: Long,
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    var title: String,
    // 내장 테마 번호. custom 테마 사용 시 theme_id 참조
    @JdbcTypeCode(SqlTypes.TINYINT)
    var theme: BasicThemeType,
    var themeId: Long? = null,
    var isPrimary: Boolean = false,
) : ExternalIdEntity()
