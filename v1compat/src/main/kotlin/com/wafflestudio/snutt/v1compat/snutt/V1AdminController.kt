package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonAlias
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
import com.wafflestudio.snutt.core.domain.user.service.UserService
import com.wafflestudio.snutt.v1compat.auth.V1AdminOnly
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

data class LegacyInsertNotificationRequest(
    val userId: String? = null,
    val title: String,
    @param:JsonAlias("body")
    val message: String,
    val insertFcm: Boolean = false,
    val shouldSendAsDataMessage: Boolean = false,
    val type: NotificationType = NotificationType.NORMAL,
    val dataPayload: Map<String, String> = emptyMap(),
    val deeplink: String? = null,
)

data class LegacyConfigVersion(
    val ios: String?,
    val android: String?,
)

data class LegacyConfigResponse(
    val id: Long,
    val data: JsonNode,
    val minVersion: LegacyConfigVersion?,
    val maxVersion: LegacyConfigVersion?,
)

data class LegacyAdminConfigWriteRequest(
    val data: JsonNode? = null,
    val minVersion: LegacyConfigVersion? = null,
    val maxVersion: LegacyConfigVersion? = null,
)

data class LegacyAdminPopupWriteRequest(
    @param:JsonAlias("key")
    val popupKey: String,
    val imageOriginUri: String,
    val linkUrl: String? = null,
    val hiddenDays: Int? = null,
)

data class LegacyAdminUserSearchResponse(
    val id: String,
    val email: String?,
    val isEmailVerified: Boolean,
    val nickname: String,
    val localId: String?,
    val isAdmin: Boolean,
    val active: Boolean,
    val regDate: Instant,
    val lastLoginTimestamp: Long,
    val authProviders: List<AuthProvider>,
    val socialAccounts: LegacySocialAccounts,
)

data class LegacySocialAccounts(
    val googleEmail: String?,
    val kakaoEmail: String?,
    val appleEmail: String?,
    val facebookName: String?,
)

data class LegacyAdminDiaryQuestionWriteRequest(
    val question: String,
    val shortQuestion: String,
    val answers: List<String>,
    val shortAnswers: List<String>,
    val targetDailyClassTypes: List<String>,
)

@RestController
@V1AdminOnly
@RequestMapping("/v1/admin", "/admin")
class V1AdminController(
    private val notificationService: NotificationService,
    private val pushService: PushService,
    private val configService: ClientConfigService,
    private val popupService: PopupService,
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
    private val userService: UserService,
    private val diaryService: DiaryService,
    private val uploadUriIssuer: UploadUriIssuer,
) {
    private companion object {
        const val MAX_UPLOAD_FILE_COUNT = 10
    }

    private val configJsonMapper = JsonMapper.builder().build()

    @PostMapping("/images/{source}/upload-uris")
    fun getUploadUris(
        @PathVariable source: String,
        @RequestParam(defaultValue = "1") count: Int,
    ): List<FileUploadUri> {
        val storageSource = StorageSource.from(source) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        if (count !in 1..MAX_UPLOAD_FILE_COUNT) throw SnuttException(ErrorType.TOO_MANY_FILES)
        return uploadUriIssuer.issue(storageSource, count)
    }

    @PostMapping("/notifications", "/insert_noti")
    fun insertNotification(
        @RequestBody body: LegacyInsertNotificationRequest,
    ) {
        val userId = body.userId?.let { userService.get(it.toLong()).id }
        if (body.insertFcm) {
            val preferenceType =
                when (body.type) {
                    NotificationType.LECTURE_UPDATE -> PushPreferenceType.LECTURE_UPDATE
                    NotificationType.LECTURE_VACANCY -> PushPreferenceType.VACANCY_NOTIFICATION
                    NotificationType.DIARY -> PushPreferenceType.DIARY
                    else -> PushPreferenceType.NORMAL
                }
            if (userId != null) {
                pushService.sendPushAndNotification(
                    userIds = listOf(userId),
                    title = body.title,
                    body = body.message,
                    type = body.type,
                    preferenceType = preferenceType,
                    urlScheme = body.deeplink,
                    shouldSendAsDataMessage = body.shouldSendAsDataMessage,
                    data = body.dataPayload,
                )
            } else {
                pushService.sendGlobalPushAndNotification(
                    title = body.title,
                    body = body.message,
                    type = body.type,
                    urlScheme = body.deeplink,
                    shouldSendAsDataMessage = body.shouldSendAsDataMessage,
                    data = body.dataPayload,
                )
            }
            return
        }
        notificationService.sendNotification(
            Notification(
                userId = userId,
                title = body.title,
                message = body.message,
                type = body.type,
                deeplink = body.deeplink,
            ),
        )
    }

    // 구버전 계약: {data, minVersion{ios,android}, maxVersion}, POST는 동일 버전 범위 설정을 재사용한다
    @PostMapping("/configs/{name}")
    fun postConfig(
        @PathVariable name: String,
        @RequestBody body: LegacyAdminConfigWriteRequest,
    ): LegacyConfigResponse {
        val data = body.data ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val writeRequest =
            ClientConfigWriteRequest(
                value = configJsonMapper.writeValueAsString(data),
                minIosVersion = body.minVersion?.ios,
                maxIosVersion = body.maxVersion?.ios,
                minAndroidVersion = body.minVersion?.android,
                maxAndroidVersion = body.maxVersion?.android,
            )
        val sameRange =
            configService.getConfigsByName(name).firstOrNull { config ->
                config.minIosVersion == writeRequest.minIosVersion &&
                    config.maxIosVersion == writeRequest.maxIosVersion &&
                    config.minAndroidVersion == writeRequest.minAndroidVersion &&
                    config.maxAndroidVersion == writeRequest.maxAndroidVersion
            }
        val saved =
            if (sameRange != null) {
                configService.patchConfig(name, checkNotNull(sameRange.id), writeRequest)
            } else {
                configService.postConfig(name, writeRequest)
            }
        return saved.toLegacyConfigResponse()
    }

    @GetMapping("/configs/{name}")
    fun getConfigs(
        @PathVariable name: String,
    ): List<LegacyConfigResponse> = configService.getConfigsByName(name).map { it.toLegacyConfigResponse() }

    @PatchMapping("/configs/{name}/{configId}")
    fun patchConfig(
        @PathVariable name: String,
        @PathVariable configId: Long,
        @RequestBody body: LegacyAdminConfigWriteRequest,
    ): LegacyConfigResponse {
        val current =
            configService.getConfigsByName(name).firstOrNull { it.id == configId }
                ?: throw SnuttException(ErrorType.CONFIG_NOT_FOUND)
        // 구버전과 동일하게 누락된 필드는 기존 값을 유지한다(merge)
        val merged =
            ClientConfigWriteRequest(
                value = body.data?.let(configJsonMapper::writeValueAsString) ?: current.value,
                minIosVersion = body.minVersion?.ios ?: current.minIosVersion,
                maxIosVersion = body.maxVersion?.ios ?: current.maxIosVersion,
                minAndroidVersion = body.minVersion?.android ?: current.minAndroidVersion,
                maxAndroidVersion = body.maxVersion?.android ?: current.maxAndroidVersion,
            )
        return configService.patchConfig(name, configId, merged).toLegacyConfigResponse()
    }

    @DeleteMapping("/configs/{name}/{configId}")
    fun deleteConfig(
        @PathVariable name: String,
        @PathVariable configId: Long,
    ) {
        configService.deleteConfig(name, configId)
    }

    private fun ClientConfig.toLegacyConfigResponse(): LegacyConfigResponse =
        LegacyConfigResponse(
            id = checkNotNull(id),
            data = configJsonMapper.readTree(value),
            minVersion =
                minIosVersion?.let { min ->
                    maxIosVersion?.let { max -> LegacyConfigVersion(min, max) }
                },
            maxVersion =
                minAndroidVersion?.let { min ->
                    maxAndroidVersion?.let { max -> LegacyConfigVersion(min, max) }
                },
        )

    @PostMapping("/popups")
    fun postPopup(
        @RequestBody body: LegacyAdminPopupWriteRequest,
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

    @GetMapping("/registration-periods", "/registrationPeriods")
    fun getSemesterRegistrationPeriods(): List<SemesterRegistrationPeriod> = semesterRegistrationPeriodService.getAll()

    @GetMapping("/registration-periods/{year}/{semester}", "/registrationPeriods/{year}/{semester}")
    fun getSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Int,
    ): SemesterRegistrationPeriod? = semesterRegistrationPeriodService.getByYearAndSemester(year, parseSemester(semester))

    @PatchMapping("/registration-periods/{year}/{semester}", "/registrationPeriods/{year}/{semester}")
    fun patchSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestBody registrationPeriods: List<RegistrationDate>,
    ) {
        semesterRegistrationPeriodService.upsert(year, parseSemester(semester), registrationPeriods)
    }

    @DeleteMapping("/registration-periods/{year}/{semester}", "/registrationPeriods/{year}/{semester}")
    fun deleteSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Int,
    ) {
        semesterRegistrationPeriodService.delete(year, parseSemester(semester))
    }

    @GetMapping("/users/search")
    fun searchUsersByEmail(
        @RequestParam email: String,
    ): List<LegacyAdminUserSearchResponse> =
        userService.searchByEmailWithAuthInfo(email).map { info ->
            val user = info.user
            LegacyAdminUserSearchResponse(
                id = user.id!!.toString(),
                email = user.email,
                nickname = user.nickname,
                localId = user.localId,
                isAdmin = user.isAdmin,
                isEmailVerified = user.isEmailVerified,
                active = user.active,
                regDate = checkNotNull(user.createdAt),
                lastLoginTimestamp = user.lastLoginAt.toEpochMilli(),
                authProviders = info.authProviders,
                socialAccounts =
                    LegacySocialAccounts(
                        googleEmail = info.socialAuths.firstOrNull { it.provider == AuthProvider.GOOGLE }?.email,
                        kakaoEmail = info.socialAuths.firstOrNull { it.provider == AuthProvider.KAKAO }?.email,
                        appleEmail = info.socialAuths.firstOrNull { it.provider == AuthProvider.APPLE }?.email,
                        facebookName = info.socialAuths.firstOrNull { it.provider == AuthProvider.FACEBOOK }?.displayName,
                    ),
            )
        }

    @GetMapping("/diary/daily-class-types", "/diary/dailyClassTypes")
    fun getAllDiaryDailyClassTypes(): List<DiaryDailyClassType> = diaryService.getAllDailyClassTypes()

    @GetMapping("/diary/questions")
    fun getDiaryQuestions(): List<DiaryQuestion> = diaryService.getActiveQuestions()

    @PostMapping("/diary/daily-class-types", "/diary/dailyClassTypes")
    fun insertDiaryDailyClassType(
        @RequestParam name: String,
    ) {
        diaryService.addOrEnableDailyClassType(name)
    }

    @DeleteMapping("/diary/daily-class-types", "/diary/dailyClassTypes")
    fun removeDiaryDailyClassType(
        @RequestParam name: String,
    ) {
        diaryService.disableDailyClassType(name)
    }

    @PostMapping("/diary/questions")
    fun insertDiaryQuestion(
        @RequestBody body: LegacyAdminDiaryQuestionWriteRequest,
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

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
