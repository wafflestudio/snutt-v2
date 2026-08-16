package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.diary.model.QuestionAnswer
import com.wafflestudio.snutt.core.domain.diary.service.DiaryQuestionnaireRequest
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
import com.wafflestudio.snutt.core.domain.diary.service.DiarySubmissionRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.lecture.service.LectureVocabularyService
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyOkResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyPageResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.toLegacyLocalDateTime
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

data class LegacyThemeDto(
    val id: String?,
    val userId: String,
    val theme: Int,
    val name: String,
    val colors: List<ColorSet>?,
    val isDefault: Boolean,
    val isCustom: Boolean,
    val origin: LegacyThemeOriginDto?,
    val status: ThemeStatus,
    val publishInfo: LegacyThemePublishInfoDto?,
)

data class LegacyThemeOriginDto(
    val originId: String,
    val authorId: String?,
)

data class LegacyThemePublishInfoDto(
    val publishName: String,
    val authorName: String?,
    val downloads: Long,
)

private fun TimetableThemeDisplay.toLegacy(
    userExternalId: String,
    origin: LegacyThemeOriginDto?,
) = LegacyThemeDto(
    id = id,
    userId = userExternalId,
    theme = (if (isCustom) BasicThemeType.SNUTT else BasicThemeType.from(name) ?: BasicThemeType.SNUTT).value,
    name = name,
    colors = colors,
    isDefault = isDefault,
    isCustom = isCustom,
    origin = origin,
    status = status,
    publishInfo =
        publishName?.let {
            LegacyThemePublishInfoDto(
                publishName = it,
                authorName = authorNickname,
                downloads = downloadCount,
            )
        },
)

data class LegacyThemeSearchRequest(
    val keyword: String,
)

data class LegacyThemePublishRequest(
    val publishName: String,
    val isAnonymous: Boolean,
)

data class LegacyThemeAddRequest(
    val name: String,
    val colors: List<ColorSet>,
)

data class LegacyThemeModifyRequest(
    val name: String? = null,
    val colors: List<ColorSet>? = null,
)

data class LegacyThemeDownloadRequest(
    val name: String,
)

@RestController
@RequestMapping("/v1/themes")
class V1CompatThemeController(
    private val timetableThemeService: TimetableThemeService,
) {
    private fun originMap(displays: List<TimetableThemeDisplay>): Map<String, LegacyThemeOriginDto> {
        val downloaded = displays.filter { it.status == ThemeStatus.DOWNLOADED }
        if (downloaded.isEmpty()) return emptyMap()
        return timetableThemeService
            .getOrigins(downloaded.mapNotNull { it.id })
            .mapValues { (_, origin) ->
                LegacyThemeOriginDto(
                    originId = origin.originThemeExternalId,
                    authorId = origin.authorExternalId,
                )
            }
    }

    @GetMapping("")
    fun getThemes(
        @V1CurrentUser user: User,
    ): List<LegacyThemeDto> {
        val themes = timetableThemeService.getThemes(user.id!!)
        val origins = originMap(themes)
        return themes.map { it.toLegacy(user.externalId, origins[it.id]) }
    }

    @GetMapping("/best")
    fun getBestThemes(
        @V1CurrentUser user: User,
        @RequestParam page: Int,
    ): LegacyPageResponse<LegacyThemeDto> = wrap(user, timetableThemeService.getBestThemes(page))

    @GetMapping("/friends")
    fun getFriendsThemes(
        @V1CurrentUser user: User,
        @RequestParam page: Int,
    ): LegacyPageResponse<LegacyThemeDto> = wrap(user, timetableThemeService.getFriendsThemes(user.id!!, page))

    @PostMapping("/search")
    fun searchThemes(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyThemeSearchRequest,
    ): LegacyPageResponse<LegacyThemeDto> = wrap(user, timetableThemeService.searchThemes(body.keyword))

    @GetMapping("/{themeId}")
    fun getTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = single(user, timetableThemeService.getTheme(user.id!!, themeId, null))

    @PostMapping("")
    fun addTheme(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyThemeAddRequest,
    ): LegacyThemeDto = timetableThemeService.addTheme(user.id!!, body.name, body.colors).toLegacy(user.externalId, null)

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemeModifyRequest,
    ): LegacyThemeDto = single(user, timetableThemeService.modifyTheme(user.id!!, themeId, body.name, body.colors))

    @DeleteMapping("/{themeId}")
    fun deleteTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ) {
        timetableThemeService.deleteTheme(user.id!!, themeId)
    }

    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemePublishRequest,
    ): LegacyOkResponse {
        timetableThemeService.publishTheme(user.id!!, themeId, body.publishName, body.isAnonymous)
        return LegacyOkResponse()
    }

    @DeleteMapping("/{themeId}/publish")
    fun deletePublishedTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ) {
        timetableThemeService.deletePublishedTheme(user.id!!, themeId)
    }

    @PostMapping("/{themeId}/download")
    fun downloadTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemeDownloadRequest,
    ): LegacyThemeDto = single(user, timetableThemeService.downloadTheme(user.id!!, themeId, body.name))

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = timetableThemeService.copyTheme(user.id!!, themeId).toLegacy(user.externalId, null)

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = single(user, timetableThemeService.setDefault(user.id!!, themeId))

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = timetableThemeService.unsetDefault(user.id!!, themeId).toLegacy(user.externalId, null)

    @PostMapping("/basic/{basicThemeTypeValue}/default")
    fun setBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): LegacyThemeDto {
        BasicThemeType.fromValue(basicThemeTypeValue)
        return timetableThemeService.setBasicThemeDefault(user.id!!).toLegacy(user.externalId, null)
    }

    @DeleteMapping("/basic/{basicThemeTypeValue}/default")
    fun unsetBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): LegacyThemeDto =
        timetableThemeService
            .unsetBasicThemeDefault(user.id!!, BasicThemeType.fromValue(basicThemeTypeValue))
            .toLegacy(user.externalId, null)

    private fun wrap(
        user: User,
        themes: List<TimetableThemeDisplay>,
    ): LegacyPageResponse<LegacyThemeDto> {
        val origins = originMap(themes)
        val content = themes.map { it.toLegacy(user.externalId, origins[it.id]) }
        return LegacyPageResponse(content = content, totalCount = content.size)
    }

    private fun single(
        user: User,
        theme: TimetableThemeDisplay,
    ): LegacyThemeDto = theme.toLegacy(user.externalId, originMap(listOf(theme))[theme.id])
}

data class LegacyDiaryQuestionnaireRequest(
    val lectureId: String,
    val dailyClassTypes: List<String>,
)

data class LegacyDiarySubmissionRequest(
    val lectureId: String,
    val dailyClassTypes: List<String>,
    val questionAnswers: List<QuestionAnswer>,
    val comment: String,
)

data class LegacyDiaryQuestionnaireResponse(
    val courseTitle: String,
    val questions: List<LegacyDiaryQuestionDto>,
    val nextLecture: LegacyDiaryTargetLectureDto?,
)

data class LegacyDiaryQuestionDto(
    val id: Long?,
    val question: String,
    val answers: List<String>,
)

data class LegacyDiaryTargetLectureDto(
    val lectureId: String?,
    val courseTitle: String,
)

data class LegacyDiaryDailyClassTypeDto(
    val id: String,
    val name: String,
)

data class LegacyDiarySemesterSubmissionsDto(
    val year: Int,
    val semester: Int,
    val submissions: List<LegacyDiarySubmissionDto>,
)

data class LegacyDiarySubmissionDto(
    val id: String,
    val lectureId: String?,
    val date: LocalDateTime,
    val courseTitle: String,
    val shortQuestionReplies: List<LegacyDiaryShortQuestionReplyDto>,
    val comment: String,
)

data class LegacyDiaryShortQuestionReplyDto(
    val question: String,
    val answer: String,
)

@RestController
@RequestMapping("/v1/diary")
class V1CompatDiaryController(
    private val diaryService: DiaryService,
    private val lectureService: LectureService,
) {
    @PostMapping("/questionnaire")
    fun getQuestionnaire(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyDiaryQuestionnaireRequest,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyDiaryQuestionnaireResponse {
        val display =
            diaryService.generateQuestionnaire(
                user.id!!,
                DiaryQuestionnaireRequest(lectureId = body.lectureId, dailyClassTypes = body.dailyClassTypes),
            )
        return LegacyDiaryQuestionnaireResponse(
            courseTitle = clientInfo.language.select(display.courseTitle, display.courseTitleEn),
            questions =
                display.questions.map {
                    LegacyDiaryQuestionDto(id = it.id, question = it.question, answers = it.answerList)
                },
            nextLecture =
                display.nextLecture?.let {
                    LegacyDiaryTargetLectureDto(
                        lectureId = it.lectureId,
                        courseTitle = clientInfo.language.select(it.courseTitle, it.courseTitleEn),
                    )
                },
        )
    }

    @GetMapping("/target")
    fun getRandomTargetLecture(
        @V1CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Int,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyDiaryTargetLectureDto {
        val target =
            diaryService.getDiaryTargetLecture(user.id!!, year, Semester.fromValue(semester), emptyList())
                ?: throw SnuttException(ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND)
        return LegacyDiaryTargetLectureDto(
            lectureId = target.lectureId,
            courseTitle = clientInfo.language.select(target.courseTitle, target.courseTitleEn),
        )
    }

    @GetMapping("/dailyClassTypes")
    fun getDailyClassTypes(
        @V1CurrentUser user: User,
    ): List<LegacyDiaryDailyClassTypeDto> =
        diaryService
            .getAllDailyClassTypes()
            .map { LegacyDiaryDailyClassTypeDto(id = it.externalId, name = it.name) }

    @GetMapping("/my")
    fun getMySubmissions(
        @V1CurrentUser user: User,
    ): List<LegacyDiarySemesterSubmissionsDto> {
        val submissions = diaryService.getMySubmissions(user.id!!)
        val replies = diaryService.getSubmissionIdShortQuestionRepliesMap(submissions)
        val lectureExternalIds =
            lectureService
                .getAllByIds(submissions.mapNotNull { it.lectureId })
                .mapValues { (_, lecture) -> lecture.externalId }
        return submissions
            .groupBy { it.year to it.semester }
            .map { (yearSemester, group) ->
                LegacyDiarySemesterSubmissionsDto(
                    year = yearSemester.first,
                    semester = yearSemester.second.value,
                    submissions =
                        group.map { submission ->
                            LegacyDiarySubmissionDto(
                                id = submission.externalId,
                                lectureId = submission.lectureId?.let(lectureExternalIds::get),
                                date = checkNotNull(submission.createdAt).toLegacyLocalDateTime(),
                                courseTitle = submission.courseTitle,
                                shortQuestionReplies =
                                    (replies[submission.id] ?: emptyList()).map {
                                        LegacyDiaryShortQuestionReplyDto(question = it.shortQuestion, answer = it.shortAnswer)
                                    },
                                comment = submission.comment,
                            )
                        },
                )
            }
    }

    @PostMapping("")
    fun submitDiary(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyDiarySubmissionRequest,
    ) {
        diaryService.submitDiary(
            user.id!!,
            DiarySubmissionRequest(
                lectureId = body.lectureId,
                dailyClassTypes = body.dailyClassTypes,
                questionAnswers = body.questionAnswers,
                comment = body.comment,
            ),
        )
    }

    @DeleteMapping("/{submissionId}")
    fun removeDiarySubmission(
        @V1CurrentUser user: User,
        @PathVariable submissionId: String,
    ) {
        diaryService.removeSubmission(submissionId, user.id!!)
    }
}

data class LegacyTagUpdateTimeResponse(
    @param:JsonProperty("updated_at")
    val updatedAt: Long?,
)

@RestController
@RequestMapping("/v1/tags")
class V1CompatTagUpdateTimeController(
    private val lectureVocabularyService: LectureVocabularyService,
) {
    @GetMapping("/{year}/{semester}/update_time")
    fun getTagListUpdateTime(
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTagUpdateTimeResponse {
        val vocabulary =
            lectureVocabularyService.getVocabulary(year, Semester.fromValue(semester), clientInfo.language)
        return LegacyTagUpdateTimeResponse(updatedAt = vocabulary.updatedAt?.toEpochMilli())
    }
}

data class LegacyReminderModifyRequest(
    val option: TimetableLectureReminderOption,
)

data class LegacyReminderDto(
    val timetableLectureId: String,
    val courseTitle: String,
    val option: TimetableLectureReminderOption,
)

@RestController
@RequestMapping("/v1/tables/{timetableId}/lecture")
class V1CompatReminderController(
    private val timetableLectureReminderService: TimetableLectureReminderService,
) {
    @GetMapping("/reminders")
    fun getReminders(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
    ): List<LegacyReminderDto> =
        timetableLectureReminderService
            .getReminders(user.id!!, timetableId)
            .map { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    @GetMapping("/{timetableLectureId}/reminder")
    fun getReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
    ): LegacyReminderDto =
        timetableLectureReminderService
            .getReminder(user.id!!, timetableId, timetableLectureId)
            .let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    @PutMapping("/{timetableLectureId}/reminder")
    fun modifyReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
        @RequestBody body: LegacyReminderModifyRequest,
    ): LegacyReminderDto =
        timetableLectureReminderService
            .modifyReminder(user.id!!, timetableId, timetableLectureId, body.option)
            .let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    private fun legacyReminder(
        timetableLectureId: String,
        courseTitle: String,
        option: TimetableLectureReminderOption,
    ) = LegacyReminderDto(
        timetableLectureId = timetableLectureId,
        courseTitle = courseTitle,
        option = option,
    )
}

data class LegacyLectureEvSummaryResponse(
    val evLectureId: Long?,
    val avgRating: Double?,
    val evaluationCount: Long,
)

@RestController
@RequestMapping("/v1/ev")
class V1CompatEvSummaryController(
    private val evaluationService: EvaluationService,
) {
    @V1Public
    @GetMapping("/lectures/{lectureId}/summary")
    fun getLectureEvaluationSummary(
        @PathVariable lectureId: String,
    ): LegacyLectureEvSummaryResponse {
        val lecture = evaluationService.getEvaluationSummaryOfLecture(lectureId).lecture
        val summary = lecture.id?.let { evaluationService.findSummariesByLectureIds(listOf(it))[it] }
        return LegacyLectureEvSummaryResponse(
            evLectureId = lecture.courseId,
            avgRating = summary?.avgRating,
            evaluationCount = summary?.evalCount ?: 0L,
        )
    }
}
