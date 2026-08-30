package com.wafflestudio.snutt.api.v2.admin

import com.fasterxml.jackson.annotation.JsonAlias
import com.wafflestudio.snutt.api.auth.AdminOnly
import com.wafflestudio.snutt.api.scheduler.DiaryScheduler
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.storage.FileUploadUri
import com.wafflestudio.snutt.core.common.storage.StorageSource
import com.wafflestudio.snutt.core.common.storage.UploadUriIssuer
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.clientconfig.model.ClientConfig
import com.wafflestudio.snutt.core.domain.clientconfig.service.ClientConfigService
import com.wafflestudio.snutt.core.domain.clientconfig.service.ClientConfigWriteRequest
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.NotificationService
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.popup.model.Popup
import com.wafflestudio.snutt.core.domain.popup.service.PopupService
import com.wafflestudio.snutt.core.domain.popup.service.PopupWriteRequest
import com.wafflestudio.snutt.core.domain.pushpreference.model.PushPreferenceType
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.SemesterRegistrationPeriod
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.trace.service.ApiTraceTargetDisplay
import com.wafflestudio.snutt.core.domain.trace.service.ApiTraceTargetService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import jakarta.validation.Valid
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
    val userId: Long? = null,
    @field:NotBlank val title: String,
    @field:NotBlank
    @param:JsonAlias("body")
    val message: String,
    val type: NotificationType = NotificationType.NORMAL,
    val deeplink: String? = null,
    val sendPush: Boolean = false,
)

data class AdminConfigWriteRequest(
    @field:NotBlank val value: String,
    val minIosVersion: String? = null,
    val maxIosVersion: String? = null,
    val minAndroidVersion: String? = null,
    val maxAndroidVersion: String? = null,
)

data class AdminPopupWriteRequest(
    @field:NotBlank
    @param:JsonAlias("key")
    val popupKey: String,
    @field:NotBlank val imageOriginUri: String,
    val linkUrl: String? = null,
    val hiddenDays: Int? = null,
)

data class AdminUserSearchResponse(
    val id: Long,
    val email: String?,
    val isEmailVerified: Boolean,
    val nickname: String,
    val localId: String?,
    val isAdmin: Boolean,
    val active: Boolean,
    val regDate: Long,
    val lastLoginTimestamp: Long,
    val authProviders: List<AuthProvider>,
    val socialAccounts: AdminSocialAccountsResponse,
)

data class AdminSocialAccountsResponse(
    val googleEmail: String?,
    val kakaoEmail: String?,
    val appleEmail: String?,
    val facebookName: String?,
)

data class AdminDiaryQuestionWriteRequest(
    @field:NotBlank val question: String,
    @field:NotBlank val shortQuestion: String,
    val answers: List<String>,
    val shortAnswers: List<String>,
    val targetDailyClassTypes: List<String>,
)

data class AdminApiTraceTargetRequest(
    val userId: Long,
    val memo: String? = null,
)

data class AdminApiTraceTargetResponse(
    val userId: Long,
    val nickname: String,
    val email: String?,
    val memo: String?,
    val createdAt: Long,
)

@RestController
@AdminOnly
@RequestMapping("/v2/admin")
class AdminController(
    private val notificationService: NotificationService,
    private val pushService: PushService,
    private val configService: ClientConfigService,
    private val popupService: PopupService,
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
    private val userService: UserService,
    private val diaryService: DiaryService,
    private val diaryScheduler: DiaryScheduler,
    private val apiTraceTargetService: ApiTraceTargetService,
    private val uploadUriIssuer: UploadUriIssuer,
) {
    @PostMapping("/images/{source}/upload-uris")
    fun getUploadUris(
        @PathVariable source: String,
        @RequestParam(defaultValue = "1") count: Int,
    ): List<FileUploadUri> {
        val storageSource = StorageSource.from(source) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        if (count !in 1..MAX_UPLOAD_FILE_COUNT) throw SnuttException(ErrorType.TOO_MANY_FILES)
        return uploadUriIssuer.issue(storageSource, count)
    }

    private companion object {
        const val MAX_UPLOAD_FILE_COUNT = 10
    }

    @PostMapping("/diary/notifier/trigger")
    fun triggerDiaryNotifier() {
        diaryScheduler.sendDiaryNotifications()
    }

    @PostMapping("/notifications")
    fun insertNotification(
        @Valid @RequestBody body: InsertNotificationRequest,
    ) {
        val userId = body.userId
        when {
            !body.sendPush ->
                notificationService.sendNotification(
                    Notification(
                        userId = userId,
                        title = body.title,
                        message = body.message,
                        type = body.type,
                        deeplink = body.deeplink,
                    ),
                )
            userId != null ->
                pushService.sendPushAndNotification(
                    userIds = listOf(userId),
                    title = body.title,
                    body = body.message,
                    type = body.type,
                    preferenceType = PushPreferenceType.NORMAL,
                    urlScheme = body.deeplink,
                )
            else ->
                pushService.sendGlobalPushAndNotification(
                    title = body.title,
                    body = body.message,
                    type = body.type,
                    urlScheme = body.deeplink,
                )
        }
    }

    @PostMapping("/configs/{name}")
    fun postConfig(
        @PathVariable name: String,
        @Valid @RequestBody body: AdminConfigWriteRequest,
    ): ClientConfig = configService.postConfig(name, body.toWriteRequest())

    @GetMapping("/configs/{name}")
    fun getConfigs(
        @PathVariable name: String,
    ): List<ClientConfig> = configService.getConfigsByName(name)

    @PatchMapping("/configs/{name}/{configId}")
    fun patchConfig(
        @PathVariable name: String,
        @PathVariable configId: Long,
        @Valid @RequestBody body: AdminConfigWriteRequest,
    ): ClientConfig = configService.patchConfig(name, configId, body.toWriteRequest())

    @DeleteMapping("/configs/{name}/{configId}")
    fun deleteConfig(
        @PathVariable name: String,
        @PathVariable configId: Long,
    ) {
        configService.deleteConfig(name, configId)
    }

    @PostMapping("/popups")
    fun postPopup(
        @Valid @RequestBody body: AdminPopupWriteRequest,
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
        @PathVariable popupId: Long,
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
        userService.searchByEmailWithAuthInfo(email).map { info ->
            val user = info.user
            AdminUserSearchResponse(
                id = user.id!!,
                email = user.email,
                nickname = user.nickname,
                localId = user.localId,
                isAdmin = user.isAdmin,
                isEmailVerified = user.isEmailVerified,
                active = user.active,
                regDate = checkNotNull(user.createdAt).toEpochMilli(),
                lastLoginTimestamp = user.lastLoginAt.toEpochMilli(),
                authProviders = info.authProviders,
                socialAccounts =
                    AdminSocialAccountsResponse(
                        googleEmail = info.socialAuths.firstOrNull { it.provider == AuthProvider.GOOGLE }?.email,
                        kakaoEmail = info.socialAuths.firstOrNull { it.provider == AuthProvider.KAKAO }?.email,
                        appleEmail = info.socialAuths.firstOrNull { it.provider == AuthProvider.APPLE }?.email,
                        facebookName = info.socialAuths.firstOrNull { it.provider == AuthProvider.FACEBOOK }?.displayName,
                    ),
            )
        }

    @GetMapping("/trace-targets")
    fun getApiTraceTargets(): List<AdminApiTraceTargetResponse> = apiTraceTargetService.getAll().map { it.toResponse() }

    @PostMapping("/trace-targets")
    fun addApiTraceTarget(
        @RequestBody body: AdminApiTraceTargetRequest,
    ): AdminApiTraceTargetResponse = apiTraceTargetService.add(body.userId, body.memo).toResponse()

    @DeleteMapping("/trace-targets/{userId}")
    fun removeApiTraceTarget(
        @PathVariable userId: Long,
    ) {
        apiTraceTargetService.remove(userId)
    }

    @GetMapping("/diary/daily-class-types")
    fun getAllDiaryDailyClassTypes(): List<DiaryDailyClassType> = diaryService.getAllDailyClassTypes()

    @GetMapping("/diary/questions")
    fun getDiaryQuestions(): List<DiaryQuestion> = diaryService.getActiveQuestions()

    @PostMapping("/diary/daily-class-types")
    fun insertDiaryDailyClassType(
        @RequestParam name: String,
    ) {
        diaryService.addOrEnableDailyClassType(name)
    }

    @DeleteMapping("/diary/daily-class-types")
    fun removeDiaryDailyClassType(
        @RequestParam name: String,
    ) {
        diaryService.disableDailyClassType(name)
    }

    @PostMapping("/diary/questions")
    fun insertDiaryQuestion(
        @Valid @RequestBody body: AdminDiaryQuestionWriteRequest,
    ) {
        diaryService.addQuestion(
            question = body.question,
            shortQuestion = body.shortQuestion,
            answers = body.answers,
            shortAnswers = body.shortAnswers,
            targetDailyClassTypes = body.targetDailyClassTypes,
        )
    }

    @DeleteMapping("/diary/questions/{questionId}")
    fun removeDiaryQuestion(
        @PathVariable questionId: Long,
    ) {
        diaryService.removeQuestion(questionId)
    }

    private fun AdminConfigWriteRequest.toWriteRequest() =
        ClientConfigWriteRequest(
            value = value,
            minIosVersion = minIosVersion,
            maxIosVersion = maxIosVersion,
            minAndroidVersion = minAndroidVersion,
            maxAndroidVersion = maxAndroidVersion,
        )

    private fun ApiTraceTargetDisplay.toResponse() =
        AdminApiTraceTargetResponse(
            userId = target.userId,
            nickname = user.nickname,
            email = user.email,
            memo = target.memo,
            createdAt = checkNotNull(target.createdAt).toEpochMilli(),
        )

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
