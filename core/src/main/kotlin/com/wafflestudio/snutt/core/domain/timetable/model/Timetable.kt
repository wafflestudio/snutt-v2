package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

const val TIMETABLE_TITLE_MAX_LENGTH = 100

@Entity
class Timetable(
    var userId: Long,
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    var title: String,
    var themeId: Long = 1L,
    var isPrimary: Boolean = false,
) : BaseEntity()
