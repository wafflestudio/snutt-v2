package com.wafflestudio.snutt.core.domain.registrationperiod.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.SemesterRegistrationPeriod
import com.wafflestudio.snutt.core.domain.registrationperiod.repository.SemesterRegistrationPeriodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SemesterRegistrationPeriodService(
    private val semesterRegistrationPeriodRepository: SemesterRegistrationPeriodRepository,
) {
    fun getAll(): List<SemesterRegistrationPeriod> = semesterRegistrationPeriodRepository.findAll()

    fun getByYearAndSemester(
        year: Int,
        semester: Semester,
    ): SemesterRegistrationPeriod? = semesterRegistrationPeriodRepository.findByYearAndSemester(year, semester)

    @Transactional
    fun upsert(
        year: Int,
        semester: Semester,
        registrationPeriodList: List<RegistrationDate>,
    ) {
        val period =
            semesterRegistrationPeriodRepository.findByYearAndSemester(year, semester)
                ?: SemesterRegistrationPeriod(year = year, semester = semester, registrationPeriodList = registrationPeriodList)
        period.registrationPeriodList = registrationPeriodList
        semesterRegistrationPeriodRepository.save(period)
    }

    @Transactional
    fun delete(
        year: Int,
        semester: Semester,
    ) {
        semesterRegistrationPeriodRepository.findByYearAndSemester(year, semester)?.let {
            semesterRegistrationPeriodRepository.delete(it)
        }
    }
}
