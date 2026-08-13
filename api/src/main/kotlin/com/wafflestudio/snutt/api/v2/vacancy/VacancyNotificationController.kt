package com.wafflestudio.snutt.api.v2.vacancy

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.user.model.User
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
    val id: String,
    val courseTitle: String,
    val courseNumber: String,
    val lectureNumber: String,
    val instructor: String?,
    val credit: Int,
)

private fun Lecture.toVacancyResponse(language: com.wafflestudio.snutt.core.common.client.Language) =
    VacancyNotificationLectureResponse(
        id = externalId,
        courseTitle = language.select(courseTitle, courseTitleEn),
        courseNumber = courseNumber,
        lectureNumber = lectureNumber,
        instructor = language.select(instructor, instructorEn),
        credit = credit,
    )

@RestController
@RequestMapping("/v2/vacancy-notifications")
class VacancyNotificationController(
    private val vacancyNotificationService: VacancyNotificationService,
) {
    @GetMapping("/lectures")
    fun getVacancyNotificationLectures(
        @CurrentUser user: User,
        @RequestAttribute clientInfo: ClientInfo,
    ): VacancyNotificationLecturesResponse =
        VacancyNotificationLecturesResponse(
            lectures =
                vacancyNotificationService
                    .getVacancyNotificationLectures(
                        user.id!!,
                    ).map { it.toVacancyResponse(clientInfo.language) },
        )

    @GetMapping("/lectures/{lectureId}/state")
    fun existsVacancyNotification(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Boolean = vacancyNotificationService.existsVacancyNotification(user.id!!, lectureId)

    @PostMapping("/lectures/{lectureId}")
    fun addVacancyNotification(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ) {
        vacancyNotificationService.addVacancyNotification(user.id!!, lectureId)
    }

    @DeleteMapping("/lectures/{lectureId}")
    fun deleteVacancyNotification(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ) {
        vacancyNotificationService.deleteVacancyNotification(user.id!!, lectureId)
    }
}
