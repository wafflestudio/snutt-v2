package com.wafflestudio.snutt.core.domain.timetable.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
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
    @Column(name = "theme_id", nullable = false)
    var themeId: Long = 1L,
    var isPrimary: Boolean = false,
) : BaseEntity()
