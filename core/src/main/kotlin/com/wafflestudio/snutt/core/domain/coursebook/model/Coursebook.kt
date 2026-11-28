package com.wafflestudio.snutt.core.domain.coursebook.model

import com.wafflestudio.snutt.core.common.compat.V1Entity
import com.wafflestudio.snutt.core.common.enums.Semester
import jakarta.persistence.Entity
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
class Coursebook(
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
) : V1Entity()
