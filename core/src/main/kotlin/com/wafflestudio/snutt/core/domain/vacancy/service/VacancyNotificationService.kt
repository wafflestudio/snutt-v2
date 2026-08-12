package com.wafflestudio.snutt.core.domain.vacancy.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.vacancy.model.VacancyNotification
import com.wafflestudio.snutt.core.domain.vacancy.repository.VacancyNotificationRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class VacancyNotificationService(
    private val vacancyNotificationRepository: VacancyNotificationRepository,
    private val lectureRepository: LectureRepository,
    private val coursebookService: CoursebookService,
) {
    fun getVacancyNotificationLectures(userId: Long): List<Lecture> {
        val lectureIds = vacancyNotificationRepository.findByUserId(userId).map { it.lectureId }
        return lectureRepository.findAllById(lectureIds)
    }

    fun existsVacancyNotification(
        userId: Long,
        lectureExternalId: String,
    ): Boolean {
        val lecture =
            lectureRepository.findByExternalId(lectureExternalId)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        return vacancyNotificationRepository.existsByUserIdAndLectureId(userId, lecture.id!!)
    }

    @Transactional
    fun addVacancyNotification(
        userId: Long,
        lectureExternalId: String,
    ) {
        val lecture =
            lectureRepository.findByExternalId(lectureExternalId)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val latestCoursebook = coursebookService.getLatestCoursebook()
        // 이전 학기 강의에는 빈자리 알림을 등록할 수 없다 (v1 동일)
        if (lecture.year != latestCoursebook.year || lecture.semester != latestCoursebook.semester) {
            throw SnuttException(ErrorType.INVALID_REGISTRATION_FOR_PREVIOUS_SEMESTER_COURSE)
        }
        try {
            vacancyNotificationRepository.save(
                VacancyNotification(userId = userId, lectureId = lecture.id!!),
            )
        } catch (e: DataIntegrityViolationException) {
            throw SnuttException(ErrorType.DUPLICATE_VACANCY_NOTIFICATION)
        }
    }

    @Transactional
    fun deleteVacancyNotification(
        userId: Long,
        lectureExternalId: String,
    ) {
        val lecture =
            lectureRepository.findByExternalId(lectureExternalId)
                ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        vacancyNotificationRepository.deleteByUserIdAndLectureId(userId, lecture.id!!)
    }
}
