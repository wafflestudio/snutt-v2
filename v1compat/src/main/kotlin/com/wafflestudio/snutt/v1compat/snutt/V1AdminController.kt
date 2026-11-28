package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.mail.UserMailType
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
import com.wafflestudio.snutt.core.domain.maillog.model.MailLogStatus
import com.wafflestudio.snutt.core.domain.maillog.service.MailLogService
import com.wafflestudio.snutt.core.domain.registrationperiod.model.RegistrationDate
import com.wafflestudio.snutt.core.domain.registrationperiod.model.SemesterRegistrationPeriod
import com.wafflestudio.snutt.core.domain.registrationperiod.service.SemesterRegistrationPeriodService
import com.wafflestudio.snutt.core.domain.user.service.UserService
import com.wafflestudio.snutt.v1compat.auth.V1AdminOnly
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

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

data class LegacyAdminMailLogResponse(
    val id: String,
    val type: UserMailType,
    val status: MailLogStatus,
    val email: String?,
    val requestedLocalId: String?,
    val subject: String?,
    val body: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class LegacyRegistrationPeriodDto(
    val id: String,
    val year: Int,
    val semester: Semester,
    val registrationPeriodList: List<RegistrationDate>,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class LegacyAdminDiaryClassTypeDto(
    val id: String,
    val name: String,
    val active: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class LegacyAdminDiaryQuestionDto(
    val id: String,
    val question: String,
    val shortQuestion: String,
    val answerList: List<String>,
    val shortAnswerList: List<String>,
    val active: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

private fun SemesterRegistrationPeriod.toLegacy() =
    LegacyRegistrationPeriodDto(idV1, year, semester, registrationPeriodList, createdAt, updatedAt)

@RestController
@V1AdminOnly
@RequestMapping("/v1/admin")
class V1AdminController(
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
    private val userService: UserService,
    private val diaryService: DiaryService,
    private val mailLogService: MailLogService,
) {
    @GetMapping("/registrationPeriods")
    fun getSemesterRegistrationPeriods(): List<LegacyRegistrationPeriodDto> =
        semesterRegistrationPeriodService.getAll().map { it.toLegacy() }

    @GetMapping("/registrationPeriods/{year}/{semester}")
    fun getSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Semester,
    ): LegacyRegistrationPeriodDto? = semesterRegistrationPeriodService.getByYearAndSemester(year, semester)?.toLegacy()

    @GetMapping("/users/search")
    fun searchUsersByEmail(
        @RequestParam email: String,
    ): List<LegacyAdminUserSearchResponse> =
        userService.searchByEmailWithAuthInfo(email).map { info ->
            val user = info.user
            LegacyAdminUserSearchResponse(
                id = user.idV1,
                email = user.email,
                nickname = "${user.nickname}#${user.nicknameTag}",
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

    @GetMapping("/mailLogs")
    fun getMailLogs(
        @RequestParam email: String?,
        @RequestParam localId: String?,
    ): List<LegacyAdminMailLogResponse> =
        mailLogService.find(email, localId).map { log ->
            LegacyAdminMailLogResponse(
                id = log.idV1,
                type = log.type,
                status = log.status,
                email = log.email,
                requestedLocalId = log.requestedLocalId,
                subject = log.subject,
                body = log.body,
                errorMessage = log.errorMessage,
                createdAt = checkNotNull(log.createdAt).toEpochMilli(),
                updatedAt = checkNotNull(log.updatedAt).toEpochMilli(),
            )
        }

    @GetMapping("/diary/dailyClassTypes")
    fun getAllDiaryDailyClassTypes(): List<LegacyAdminDiaryClassTypeDto> =
        diaryService.getAllDailyClassTypes().map { LegacyAdminDiaryClassTypeDto(it.idV1, it.name, it.active, it.createdAt, it.updatedAt) }

    @GetMapping("/diary/questions")
    fun getDiaryQuestions(): List<LegacyAdminDiaryQuestionDto> =
        diaryService.getActiveQuestions().map {
            LegacyAdminDiaryQuestionDto(
                it.idV1,
                it.question,
                it.shortQuestion,
                it.answerList,
                it.shortAnswerList,
                it.active,
                it.createdAt,
                it.updatedAt,
            )
        }
}
