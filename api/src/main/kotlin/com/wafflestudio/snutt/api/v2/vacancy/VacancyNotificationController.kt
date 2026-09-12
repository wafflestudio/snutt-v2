package com.wafflestudio.snutt.api.v2.vacancy

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.domain.vacancy.service.VacancyLectureDisplay
import com.wafflestudio.snutt.core.domain.vacancy.service.VacancyNotificationService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class VacancyNotificationLecturesResponse(
    val lectures: List<VacancyNotificationLectureResponse>,
)

data class VacancyNotificationLectureResponse(
    val id: Long,
    val courseTitle: String,
    val courseNumber: String,
    val lectureNumber: String,
    val instructor: String?,
    val credit: Int,
    val quota: Int,
    val registrationCount: Int,
    val wasFull: Boolean,
)

private fun VacancyLectureDisplay.toResponse(language: Language) =
    VacancyNotificationLectureResponse(
        id = lecture.id!!,
        courseTitle = language.select(lecture.courseTitle, lecture.courseTitleEn),
        courseNumber = lecture.courseNumber,
        lectureNumber = lecture.lectureNumber,
        instructor = language.select(lecture.instructor, lecture.instructorEn),
        credit = lecture.credit,
        quota = lecture.quota,
        registrationCount = status?.registrationCount ?: 0,
        wasFull = status?.wasFull ?: false,
    )

@RestController
@RequestMapping("/v2/vacancy-notifications")
class VacancyNotificationController(
    private val vacancyNotificationService: VacancyNotificationService,
) {
    @GetMapping("/lectures")
    fun getVacancyNotificationLectures(
        @CurrentUserId userId: Long,
        @RequestAttribute clientInfo: ClientInfo,
    ): VacancyNotificationLecturesResponse =
        VacancyNotificationLecturesResponse(
            lectures =
                vacancyNotificationService
                    .getVacancyNotificationLectures(userId)
                    .map { it.toResponse(clientInfo.language) },
        )

    @GetMapping("/lectures/{lectureId}/state")
    fun existsVacancyNotification(
        @CurrentUserId userId: Long,
        @PathVariable lectureId: Long,
    ): Boolean = vacancyNotificationService.existsVacancyNotification(userId, lectureId)

    @PostMapping("/lectures/{lectureId}")
    fun addVacancyNotification(
        @CurrentUserId userId: Long,
        @PathVariable lectureId: Long,
    ) {
        vacancyNotificationService.addVacancyNotification(userId, lectureId)
    }

    @DeleteMapping("/lectures/{lectureId}")
    fun deleteVacancyNotification(
        @CurrentUserId userId: Long,
        @PathVariable lectureId: Long,
    ) {
        vacancyNotificationService.deleteVacancyNotification(userId, lectureId)
    }
}
