package com.wafflestudio.snutt.core.domain.registrationperiod.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.registrationperiod.model.SemesterRegistrationPeriod
import org.springframework.data.jpa.repository.JpaRepository

interface SemesterRegistrationPeriodRepository : JpaRepository<SemesterRegistrationPeriod, Long> {
    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): SemesterRegistrationPeriod?
}
