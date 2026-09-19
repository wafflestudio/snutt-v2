package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.diary.model.DiaryDailyClassType
import com.wafflestudio.snutt.core.domain.diary.model.DiaryQuestion
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
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

@RestController
@V1AdminOnly
@RequestMapping("/v1/admin", "/admin")
class V1AdminController(
    private val semesterRegistrationPeriodService: SemesterRegistrationPeriodService,
    private val userService: UserService,
    private val diaryService: DiaryService,
) {
    @GetMapping("/registration-periods", "/registrationPeriods")
    fun getSemesterRegistrationPeriods(): List<SemesterRegistrationPeriod> = semesterRegistrationPeriodService.getAll()

    @GetMapping("/registration-periods/{year}/{semester}", "/registrationPeriods/{year}/{semester}")
    fun getSemesterRegistrationPeriod(
        @PathVariable year: Int,
        @PathVariable semester: Semester,
    ): SemesterRegistrationPeriod? = semesterRegistrationPeriodService.getByYearAndSemester(year, semester)

    @GetMapping("/users/search")
    fun searchUsersByEmail(
        @RequestParam email: String,
    ): List<LegacyAdminUserSearchResponse> =
        userService.searchByEmailWithAuthInfo(email).map { info ->
            val user = info.user
            LegacyAdminUserSearchResponse(
                id = user.id!!.toString(),
                email = user.email,
                nickname = user.fullNickname,
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
}
