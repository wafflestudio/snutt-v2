package com.wafflestudio.snutt.core.domain.vacancy.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.model.LectureRegistrationStatus
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRegistrationStatusRepository
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class VacancyLectureDisplay(
    val lecture: Lecture,
    val status: LectureRegistrationStatus?,
)

@Service
class VacancyNotificationService(
    private val vacancyNotificationRepository: VacancyNotificationRepository,
    private val lectureRepository: LectureRepository,
    private val lectureRegistrationStatusRepository: LectureRegistrationStatusRepository,
    private val coursebookService: CoursebookService,
) {
    fun getVacancyNotificationLectures(userId: Long): List<VacancyLectureDisplay> {
        val lectureIds = vacancyNotificationRepository.findByUserId(userId).map { it.lectureId }
        val statuses = lectureRegistrationStatusRepository.findAllById(lectureIds).associateBy { it.lectureId }
        return lectureRepository.findAllById(lectureIds).map { VacancyLectureDisplay(it, statuses[it.id]) }
    }

    fun existsVacancyNotification(
        userId: Long,
        lectureId: Long,
    ): Boolean {
        val lecture =
            lectureRepository.findByIdOrNull(lectureId)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        return vacancyNotificationRepository.existsByUserIdAndLectureId(userId, lecture.id!!)
    }

    @Transactional
    fun addVacancyNotification(
        userId: Long,
        lectureId: Long,
    ) {
        val lecture =
            lectureRepository.findByIdOrNull(lectureId)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val latestCoursebook = coursebookService.getLatestCoursebook()
        if (lecture.year != latestCoursebook.year || lecture.semester != latestCoursebook.semester) {
            throw SnuttException(ErrorType.INVALID_REGISTRATION_FOR_PREVIOUS_SEMESTER_COURSE)
        }
        conflictAs(ErrorType.DUPLICATE_VACANCY_NOTIFICATION) {
            vacancyNotificationRepository.save(VacancyNotification(userId = userId, lectureId = lecture.id!!))
        }
    }

    @Transactional
    fun deleteVacancyNotification(
        userId: Long,
        lectureId: Long,
    ) {
        val lecture =
            lectureRepository.findByIdOrNull(lectureId)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        vacancyNotificationRepository.deleteByUserIdAndLectureId(userId, lecture.id!!)
    }

    fun existsVacancyNotification(
        userId: Long,
        lectureExternalId: String,
    ): Boolean = existsVacancyNotification(userId, resolveLectureIdByExternalId(lectureExternalId))

    @Transactional
    fun addVacancyNotification(
        userId: Long,
        lectureExternalId: String,
    ) {
        addVacancyNotification(userId, resolveLectureIdByExternalId(lectureExternalId))
    }

    @Transactional
    fun deleteVacancyNotification(
        userId: Long,
        lectureExternalId: String,
    ) {
        deleteVacancyNotification(userId, resolveLectureIdByExternalId(lectureExternalId))
    }

    private fun resolveLectureIdByExternalId(lectureExternalId: String): Long =
        lectureRepository.findByExternalId(lectureExternalId)?.id ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
}
