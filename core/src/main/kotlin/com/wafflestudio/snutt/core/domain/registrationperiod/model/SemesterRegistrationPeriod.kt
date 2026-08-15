package com.wafflestudio.snutt.core.domain.registrationperiod.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDate

enum class RegistrationPhase {
    CURRENT_STUDENT,
    FRESHMAN,
    COURSE_CHANGE,
}

data class RegistrationTimeSlot(
    val startMinute: Int,
    val endMinute: Int,
)

data class RegistrationDate(
    val date: LocalDate,
    val vacantSeatRegistrationTimes: List<RegistrationTimeSlot>,
    val phase: RegistrationPhase,
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
