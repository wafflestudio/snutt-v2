package com.wafflestudio.snutt.v1compat.snutt

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

private fun TimetableThemeDisplay.toLegacy(
    userExternalId: String,
    origin: Map<String, Any?>?,
) = linkedMapOf(
    "id" to id,
    "userId" to userExternalId,
    "theme" to (if (isCustom) BasicThemeType.SNUTT else BasicThemeType.from(name) ?: BasicThemeType.SNUTT).value,
    "name" to name,
    "colors" to colorList,
    "isDefault" to isDefault,
    "isCustom" to isCustom,
    "origin" to origin,
    "status" to status,
    "publishInfo" to
        publishName?.let {
            linkedMapOf(
                "publishName" to it,
                "authorName" to authorNickname,
                "downloads" to downloadCount,
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
    val colorList: List<ColorSet>,
)

data class LegacyThemeModifyRequest(
    val name: String? = null,
    val colorList: List<ColorSet>? = null,
)

data class LegacyThemeDownloadRequest(
    val name: String,
)

@RestController
@RequestMapping("/v1/themes")
class V1CompatThemeController(
    private val timetableThemeService: TimetableThemeService,
) {
    private fun originMap(displays: List<TimetableThemeDisplay>): Map<String, Map<String, Any?>> {
        val downloaded = displays.filter { it.status == ThemeStatus.DOWNLOADED }
        if (downloaded.isEmpty()) return emptyMap()
        return timetableThemeService
            .getOrigins(downloaded.mapNotNull { it.id })
            .mapValues { (_, origin) ->
                linkedMapOf(
                    "originId" to origin.originThemeExternalId,
                    "authorId" to origin.authorExternalId,
                )
            }
    }

    @GetMapping("")
    fun getThemes(
        @V1CurrentUser user: User,
    ): List<Map<String, Any?>> {
        val themes = timetableThemeService.getThemes(user.id!!)
        val origins = originMap(themes)
        return themes.map { it.toLegacy(user.externalId, origins[it.id]) }
    }

    @GetMapping("/best")
    fun getBestThemes(
        @V1CurrentUser user: User,
        @RequestParam page: Int,
    ): Map<String, Any?> = wrap(user, timetableThemeService.getBestThemes(page))

    @GetMapping("/friends")
    fun getFriendsThemes(
        @V1CurrentUser user: User,
        @RequestParam page: Int,
    ): Map<String, Any?> = wrap(user, timetableThemeService.getFriendsThemes(user.id!!, page))

    @PostMapping("/search")
    fun searchThemes(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyThemeSearchRequest,
    ): Map<String, Any?> = wrap(user, timetableThemeService.searchThemes(body.keyword))

    @GetMapping("/{themeId}")
    fun getTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): Map<String, Any?> = single(user, timetableThemeService.getTheme(user.id!!, themeId, null))

    @PostMapping("")
    fun addTheme(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyThemeAddRequest,
    ): Map<String, Any?> = timetableThemeService.addTheme(user.id!!, body.name, body.colorList).toLegacy(user.externalId, null)

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemeModifyRequest,
    ): Map<String, Any?> = single(user, timetableThemeService.modifyTheme(user.id!!, themeId, body.name, body.colorList))

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
    ): Map<String, Any?> {
        timetableThemeService.publishTheme(user.id!!, themeId, body.publishName, body.isAnonymous)
        return mapOf("message" to "ok")
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
    ): Map<String, Any?> = single(user, timetableThemeService.downloadTheme(user.id!!, themeId, body.name))

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): Map<String, Any?> = timetableThemeService.copyTheme(user.id!!, themeId).toLegacy(user.externalId, null)

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): Map<String, Any?> = single(user, timetableThemeService.setDefault(user.id!!, themeId))

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): Map<String, Any?> = timetableThemeService.unsetDefault(user.id!!, themeId).toLegacy(user.externalId, null)

    @PostMapping("/basic/{basicThemeTypeValue}/default")
    fun setBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): Map<String, Any?> {
        BasicThemeType.fromValue(basicThemeTypeValue)
        return timetableThemeService.setBasicThemeDefault(user.id!!).toLegacy(user.externalId, null)
    }

    @DeleteMapping("/basic/{basicThemeTypeValue}/default")
    fun unsetBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): Map<String, Any?> =
        timetableThemeService
            .unsetBasicThemeDefault(user.id!!, BasicThemeType.fromValue(basicThemeTypeValue))
            .toLegacy(user.externalId, null)

    private fun wrap(
        user: User,
        themes: List<TimetableThemeDisplay>,
    ): Map<String, Any?> {
        val origins = originMap(themes)
        val content = themes.map { it.toLegacy(user.externalId, origins[it.id]) }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    private fun single(
        user: User,
        theme: TimetableThemeDisplay,
    ): Map<String, Any?> = theme.toLegacy(user.externalId, originMap(listOf(theme))[theme.id])
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
    ): Map<String, Any?> {
        val display =
            diaryService.generateQuestionnaire(
                user.id!!,
                DiaryQuestionnaireRequest(lectureId = body.lectureId, dailyClassTypes = body.dailyClassTypes),
            )
        return linkedMapOf(
            "courseTitle" to clientInfo.language.select(display.courseTitle, display.courseTitleEn),
            "questions" to
                display.questions.map {
                    linkedMapOf("id" to it.id, "question" to it.question, "answers" to it.answerList)
                },
            "nextLecture" to
                display.nextLecture?.let {
                    linkedMapOf(
                        "lectureId" to it.lectureId,
                        "courseTitle" to clientInfo.language.select(it.courseTitle, it.courseTitleEn),
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
    ): Map<String, Any?> {
        val target =
            diaryService.getDiaryTargetLecture(user.id!!, year, Semester.fromValue(semester), emptyList())
                ?: throw SnuttException(ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND)
        return linkedMapOf(
            "lectureId" to target.lectureId,
            "courseTitle" to clientInfo.language.select(target.courseTitle, target.courseTitleEn),
        )
    }

    @GetMapping("/dailyClassTypes")
    fun getDailyClassTypes(
        @V1CurrentUser user: User,
    ): List<Map<String, Any?>> =
        diaryService
            .getAllDailyClassTypes()
            .map { linkedMapOf("id" to it.externalId, "name" to it.name) }

    @GetMapping("/my")
    fun getMySubmissions(
        @V1CurrentUser user: User,
    ): List<Map<String, Any?>> {
        val submissions = diaryService.getMySubmissions(user.id!!)
        val replies = diaryService.getSubmissionIdShortQuestionRepliesMap(submissions)
        val lectureExternalIds =
            lectureService
                .getAllByIds(submissions.mapNotNull { it.lectureId })
                .mapValues { (_, lecture) -> lecture.externalId }
        return submissions
            .groupBy { it.year to it.semester }
            .map { (yearSemester, group) ->
                linkedMapOf(
                    "year" to yearSemester.first,
                    "semester" to yearSemester.second.value,
                    "submissions" to
                        group.map { submission ->
                            linkedMapOf(
                                "id" to submission.externalId,
                                "lectureId" to submission.lectureId?.let(lectureExternalIds::get),
                                "date" to checkNotNull(submission.createdAt).toLegacyLocalDateTime(),
                                "courseTitle" to submission.courseTitle,
                                "shortQuestionReplies" to
                                    (replies[submission.id] ?: emptyList()).map {
                                        linkedMapOf("question" to it.shortQuestion, "answer" to it.shortAnswer)
                                    },
                                "comment" to submission.comment,
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
    ): Map<String, Any?> {
        val vocabulary =
            lectureVocabularyService.getVocabulary(year, Semester.fromValue(semester), clientInfo.language)
        return mapOf("updated_at" to vocabulary.updatedAt?.toEpochMilli())
    }
}

data class LegacyReminderModifyRequest(
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
    ): List<Map<String, Any?>> =
        timetableLectureReminderService
            .getReminders(user.id!!, timetableId)
            .map { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    @GetMapping("/{timetableLectureId}/reminder")
    fun getReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
    ): Map<String, Any?> =
        timetableLectureReminderService
            .getReminder(user.id!!, timetableId, timetableLectureId)
            .let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    @PutMapping("/{timetableLectureId}/reminder")
    fun modifyReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
        @RequestBody body: LegacyReminderModifyRequest,
    ): Map<String, Any?> =
        timetableLectureReminderService
            .modifyReminder(user.id!!, timetableId, timetableLectureId, body.option)
            .let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    private fun legacyReminder(
        timetableLectureId: String,
        courseTitle: String,
        option: TimetableLectureReminderOption,
    ) = linkedMapOf<String, Any?>(
        "timetableLectureId" to timetableLectureId,
        "courseTitle" to courseTitle,
        "option" to option,
    )
}

@RestController
@RequestMapping("/v1/ev")
class V1CompatEvSummaryController(
    private val evaluationService: EvaluationService,
) {
    @V1Public
    @GetMapping("/lectures/{lectureId}/summary")
    fun getLectureEvaluationSummary(
        @PathVariable lectureId: String,
    ): Map<String, Any?> {
        val lecture = evaluationService.getEvaluationSummaryOfLecture(lectureId).lecture
        val summary = lecture.id?.let { evaluationService.findSummariesByLectureIds(listOf(it))[it] }
        return linkedMapOf(
            "evLectureId" to lecture.courseId,
            "avgRating" to summary?.avgRating,
            "evaluationCount" to (summary?.evalCount ?: 0L),
        )
    }
}
