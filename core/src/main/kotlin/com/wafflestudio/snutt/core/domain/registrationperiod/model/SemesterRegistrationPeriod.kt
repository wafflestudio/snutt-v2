package com.wafflestudio.snutt.core.domain.registrationperiod.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

data class RegistrationDate(
    val startAt: Long,
    val endAt: Long,
    val type: String,
)

@Entity
@Table(name = "semester_registration_period")
class SemesterRegistrationPeriod(
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    @JdbcTypeCode(SqlTypes.JSON)
    var registrationPeriodList: List<RegistrationDate>,
) : ExternalIdEntity()
