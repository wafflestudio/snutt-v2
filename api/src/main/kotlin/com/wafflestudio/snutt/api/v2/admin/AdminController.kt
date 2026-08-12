package com.wafflestudio.snutt.api.v2.admin

import com.wafflestudio.snutt.api.auth.AdminOnly
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import com.wafflestudio.snutt.core.domain.clientconfig.service.ClientConfigService
import com.wafflestudio.snutt.core.domain.clientconfig.service.ClientConfigWriteRequest
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.NotificationService
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.popup.service.PopupService
import com.wafflestudio.snutt.core.domain.popup.service.PopupWriteRequest
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.SemesterRegistrationPeriod
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class InsertNotificationRequest(
    // null = 전체 공지
    val userId: String? = null,
    @field:NotBlank val title: String,
    @field:NotBlank val message: String,
    val type: NotificationType = NotificationType.NORMAL,
    val deeplink: String? = null,
)

data class AdminConfigWriteRequest(
    @field:NotBlank val value: String,
    val minIosVersion: String? = null,
    val maxIosVersion: String? = null,
    val minAndroidVersion: String? = null,
    val maxAndroidVersion: String? = null,
)

data class AdminPopupWriteRequest(
    @field:NotBlank val popupKey: String,
    @field:NotBlank val imageOriginUri: String,
    val linkUrl: String? = null,
    val hiddenDays: Int? = null,
)

data class AdminUserSearchResponse(
    val id: String,
    val email: String?,
    val nickname: String,
    val isAdmin: Boolean,
    val isEmailVerified: Boolean,
)

@RestController
@AdminOnly
@RequestMapping("/v2/admin")
class AdminController(
    private val notificationService: NotificationService,
    private val configService: ClientConfigService,
    private val popupService: PopupService,
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
    private val userRepository: UserRepository,
) {
    // FCM 발송(insertFcm)은 M7 PushService와 함께 연동한다 — 여기서는 알림함 저장만
    @PostMapping("/notifications")
    fun insertNotification(
        @RequestBody body: InsertNotificationRequest,
    ) {
        val userId = body.userId?.let { resolveUserExternalId(it) }
        notificationService.sendNotification(
            Notification(userId = userId, title = body.title, message = body.message, type = body.type, deeplink = body.deeplink),
        )
    }

    @PostMapping("/configs/{name}")
    fun postConfig(
        @PathVariable name: String,
        @RequestBody body: AdminConfigWriteRequest,
    ): ClientConfig = configService.postConfig(name, body.toWriteRequest())

    @GetMapping("/configs/{name}")
    fun getConfigs(
        @PathVariable name: String,
    ): List<ClientConfig> = configService.getConfigsByName(name)

    @PatchMapping("/configs/{name}/{configId}")
    fun patchConfig(
        @PathVariable name: String,
        @PathVariable configId: String,
        @RequestBody body: AdminConfigWriteRequest,
    ): ClientConfig = configService.patchConfig(name, configId, body.toWriteRequest())

    @DeleteMapping("/configs/{name}/{configId}")
    fun deleteConfig(
        @PathVariable name: String,
        @PathVariable configId: String,
    ) {
        configService.deleteConfig(name, configId)
    }

    @PostMapping("/popups")
    fun postPopup(
        @RequestBody body: AdminPopupWriteRequest,
    ): Popup =
        popupService.postPopup(
            PopupWriteRequest(
                popupKey = body.popupKey,
                imageOriginUri = body.imageOriginUri,
                linkUrl = body.linkUrl,
                hiddenDays = body.hiddenDays,
            ),
        )

    @DeleteMapping("/popups/{popupId}")
    fun deletePopup(
        @PathVariable popupId: String,
    ) {
        popupService.deletePopup(popupId)
    }

    @GetMapping("/registration-periods")
    fun getSemesterRegistrationPeriods(): List<SemesterRegistrationPeriod> = semesterRegistrationPeriodService.getAll()

    @GetMapping("/registration-periods/{year}/{semester}")
    fun getSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Int,
    ): SemesterRegistrationPeriod? = semesterRegistrationPeriodService.getByYearAndSemester(year, parseSemester(semester))

    @PatchMapping("/registration-periods/{year}/{semester}")
    fun patchSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestBody registrationPeriods: List<RegistrationDate>,
    ) {
        semesterRegistrationPeriodService.upsert(year, parseSemester(semester), registrationPeriods)
    }

    @DeleteMapping("/registration-periods/{year}/{semester}")
    fun deleteSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Int,
    ) {
        semesterRegistrationPeriodService.delete(year, parseSemester(semester))
    }

    @GetMapping("/users/search")
    fun searchUsersByEmail(
        @RequestParam email: String,
    ): List<AdminUserSearchResponse> =
        userRepository.findByEmailContainingIgnoreCaseAndActiveTrue(email).map {
            AdminUserSearchResponse(
                id = it.externalId,
                email = it.email,
                nickname = it.nickname,
                isAdmin = it.isAdmin,
                isEmailVerified = it.isEmailVerified,
            )
        }

    private fun AdminConfigWriteRequest.toWriteRequest() =
        ClientConfigWriteRequest(
            value = value,
            minIosVersion = minIosVersion,
            maxIosVersion = maxIosVersion,
            minAndroidVersion = minAndroidVersion,
            maxAndroidVersion = maxAndroidVersion,
        )

    private fun resolveUserExternalId(externalId: String): Long? =
        userRepository.findByExternalIdAndActiveTrue(externalId)?.id
            ?: throw SnuttException(ErrorType.USER_NOT_FOUND)

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
