package com.wafflestudio.snutt.api.v2.vacancy

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.vacancy.service.VacancyNotificationService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
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

private fun Lecture.toVacancyResponse() =
    VacancyNotificationLectureResponse(
        id = externalId,
        courseTitle = courseTitle,
        courseNumber = courseNumber,
        lectureNumber = lectureNumber,
        instructor = instructor,
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
    ): VacancyNotificationLecturesResponse =
        VacancyNotificationLecturesResponse(
            lectures = vacancyNotificationService.getVacancyNotificationLectures(user.id!!).map { it.toVacancyResponse() },
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
