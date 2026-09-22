package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.diary.model.QuestionAnswer
import com.wafflestudio.snutt.core.domain.diary.service.DiaryQuestionnaireRequest
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
import com.wafflestudio.snutt.core.domain.diary.service.DiarySubmissionRequest
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.service.LectureVocabularyService
import com.wafflestudio.snutt.core.domain.theme.dto.ThemePublicationDisplay
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.ThemeKind
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyColorSetDto
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyOkResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyPageResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.legacyBuiltinCode
import com.wafflestudio.snutt.v1compat.snutt.dto.legacyThemeValue
import org.springframework.transaction.annotation.Transactional
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
import java.time.Instant

enum class LegacyThemeStatus { BASIC, PRIVATE, PUBLISHED, DOWNLOADED }

private fun ColorSet.toLegacyColor() = LegacyColorSetDto(backgroundColor, foregroundColor)

data class LegacyThemeDto(
    val id: String?,
    val userId: String,
    val theme: Int,
    val name: String,
    val colors: List<LegacyColorSetDto>?,
    val isDefault: Boolean,
    val isCustom: Boolean,
    val origin: LegacyThemeOriginDto?,
    val status: LegacyThemeStatus,
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

private const val LEGACY_THEME_PAGE_SIZE = 10

private fun TimetableThemeDisplay.toLegacy(
    userExternalId: String,
    origin: LegacyThemeOriginDto?,
    publication: ThemePublicationDisplay? = null,
) = LegacyThemeDto(
    id = if (kind == ThemeKind.BUILTIN) null else id.toString(),
    userId = if (kind == ThemeKind.BUILTIN) userExternalId else checkNotNull(userId).toString(),
    theme = if (kind == ThemeKind.BUILTIN) legacyThemeValue(checkNotNull(builtinCode)) else 0,
    name = name,
    colors = colors.takeUnless { kind == ThemeKind.BUILTIN }?.map { it.toLegacyColor() },
    isDefault = isDefault,
    isCustom = kind != ThemeKind.BUILTIN,
    origin = origin,
    status =
        when (kind) {
            ThemeKind.BUILTIN -> LegacyThemeStatus.BASIC
            ThemeKind.DOWNLOADED -> LegacyThemeStatus.DOWNLOADED
            ThemeKind.CUSTOM -> if (publication == null) LegacyThemeStatus.PRIVATE else LegacyThemeStatus.PUBLISHED
        },
    publishInfo =
        publication?.let {
            LegacyThemePublishInfoDto(it.name, if (it.authorAnonymous) "익명" else it.authorNickname, it.downloadCount)
        },
)

private fun ThemePublicationDisplay.toLegacy() =
    LegacyThemeDto(
        id = id.toString(),
        userId = authorId?.toString().orEmpty(),
        theme = 0,
        name = name,
        colors = colors.map { it.toLegacyColor() },
        isDefault = false,
        isCustom = true,
        origin = null,
        status = LegacyThemeStatus.PUBLISHED,
        publishInfo = LegacyThemePublishInfoDto(name, if (authorAnonymous) "익명" else authorNickname, downloadCount),
    )

data class LegacyThemePublishRequest(
    val publishName: String,
    val isAnonymous: Boolean,
)

data class LegacyThemeAddRequest(
    val name: String,
    val colors: List<LegacyColorRequest>,
)

data class LegacyThemeModifyRequest(
    val name: String? = null,
    val colors: List<LegacyColorRequest>? = null,
)

data class LegacyThemeDownloadRequest(
    val name: String,
)

@RestController
@RequestMapping("/v1/themes")
class V1CompatThemeController(
    private val timetableThemeService: TimetableThemeService,
    private val publishedThemeRepository: PublishedThemeRepository,
) {
    private fun library(
        user: User,
        themes: List<TimetableThemeDisplay>,
    ): List<LegacyThemeDto> {
        val sources = publishedThemeRepository.findAllById(themes.mapNotNull { it.publicationId }).associateBy { it.id!! }
        val ownPublications =
            timetableThemeService
                .getMyPublications(user.id!!)
                .filter { it.listed && it.sourceThemeId != null }
                .sortedBy { it.id }
                .associateBy { it.sourceThemeId }
        return themes.map { theme ->
            val origin =
                theme.publicationId?.let { id ->
                    sources.getValue(id).let { LegacyThemeOriginDto(id.toString(), it.authorId?.toString()) }
                }
            theme.toLegacy(user.id!!.toString(), origin, ownPublications[theme.id])
        }
    }

    @GetMapping("")
    fun getThemes(
        @V1CurrentUser user: User,
    ): List<LegacyThemeDto> = library(user, timetableThemeService.getThemes(user.id!!))

    @GetMapping("/best")
    fun getBestThemes(
        @V1CurrentUser user: User,
        @RequestParam page: Int,
    ): LegacyPageResponse<LegacyThemeDto> = wrap(legacyPage(page) { cursor -> timetableThemeService.getPublications(cursor) })

    @GetMapping("/friends")
    fun getFriendsThemes(
        @V1CurrentUser user: User,
        @RequestParam page: Int,
    ): LegacyPageResponse<LegacyThemeDto> =
        wrap(legacyPage(page) { cursor -> timetableThemeService.getFriendsPublications(user.id!!, cursor) })

    @PostMapping("/search")
    fun searchThemes(
        @V1CurrentUser user: User,
        @RequestParam query: String,
    ): LegacyPageResponse<LegacyThemeDto> {
        val publications = mutableListOf<ThemePublicationDisplay>()
        var cursor: String? = null
        do {
            val page = timetableThemeService.getPublications(cursor, query)
            publications += page.content
            cursor = page.cursor
        } while (cursor != null)
        return wrap(publications)
    }

    @GetMapping("/{themeId}")
    fun getTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
    ): LegacyThemeDto = single(user, timetableThemeService.getTheme(user.id!!, themeId))

    @PostMapping("")
    fun addTheme(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyThemeAddRequest,
    ): LegacyThemeDto =
        timetableThemeService
            .addTheme(
                user.id!!,
                body.name,
                body.colors.map(LegacyColorRequest::requireColorSet),
            ).toLegacy(user.id!!.toString(), null)

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
        @RequestBody body: LegacyThemeModifyRequest,
    ): LegacyThemeDto =
        single(
            user,
            timetableThemeService.modifyTheme(user.id!!, themeId, body.name, body.colors?.map(LegacyColorRequest::requireColorSet)),
        )

    @DeleteMapping("/{themeId}")
    fun deleteTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
    ) {
        if (publishedThemeRepository.findBySourceThemeIdInAndListedTrue(listOf(themeId)).isNotEmpty()) {
            throw SnuttException(ErrorType.PUBLISHED_THEME_DELETE_ERROR)
        }
        timetableThemeService.deleteTheme(user.id!!, themeId)
    }

    @Transactional
    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
        @RequestBody body: LegacyThemePublishRequest,
    ): LegacyOkResponse {
        val publication = timetableThemeService.publishTheme(user.id!!, themeId, body.publishName, body.isAnonymous)
        publishedThemeRepository.findBySourceThemeIdInAndListedTrue(listOf(themeId)).filter { it.id != publication.id }.forEach {
            timetableThemeService.unpublishTheme(user.id!!, it.id!!)
        }
        return LegacyOkResponse()
    }

    @DeleteMapping("/{themeId}/publish")
    fun deletePublishedTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
    ) {
        val publications = publishedThemeRepository.findBySourceThemeIdInAndListedTrue(listOf(themeId))
        if (publications.isEmpty()) throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        publications.forEach { timetableThemeService.unpublishTheme(user.id!!, it.id!!) }
    }

    @PostMapping("/{themeId}/download")
    fun downloadTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
        @RequestBody body: LegacyThemeDownloadRequest,
    ): LegacyThemeDto = single(user, timetableThemeService.downloadTheme(user.id!!, themeId))

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
    ): LegacyThemeDto = timetableThemeService.copyTheme(user.id!!, themeId).toLegacy(user.id!!.toString(), null)

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
    ): LegacyThemeDto = single(user, timetableThemeService.setDefault(user.id!!, themeId))

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: Long,
    ): LegacyThemeDto = timetableThemeService.unsetDefault(user.id!!, themeId).toLegacy(user.id!!.toString(), null)

    @PostMapping("/basic/{basicThemeTypeValue}/default")
    fun setBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): LegacyThemeDto {
        basicThemeType(basicThemeTypeValue)
        return timetableThemeService.getDefaultTheme(user.id!!).toLegacy(user.id!!.toString(), null)
    }

    @DeleteMapping("/basic/{basicThemeTypeValue}/default")
    fun unsetBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): LegacyThemeDto {
        val basicThemeType = basicThemeType(basicThemeTypeValue)
        val current = timetableThemeService.getDefaultTheme(user.id!!)
        if (current.kind != ThemeKind.BUILTIN || current.builtinCode != legacyBuiltinCode(basicThemeType.value)) {
            throw SnuttException(ErrorType.NOT_DEFAULT_THEME_ERROR)
        }
        return current.toLegacy(user.id!!.toString(), null)
    }

    private fun <T> legacyPage(
        page: Int,
        load: (String?) -> CursorPage<T>,
    ): List<T> {
        if (page <= 0) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val end = page * LEGACY_THEME_PAGE_SIZE
        val all = mutableListOf<T>()
        var cursor: String? = null
        while (all.size < end) {
            val result = load(cursor)
            all += result.content
            cursor = result.cursor ?: break
        }
        return all.drop((page - 1) * LEGACY_THEME_PAGE_SIZE).take(LEGACY_THEME_PAGE_SIZE)
    }

    private fun basicThemeType(value: Int): BasicThemeType =
        try {
            BasicThemeType.fromValue(value)
        } catch (_: IllegalArgumentException) {
            throw SnuttException(ErrorType.INVALID_PARAMETER)
        }

    private fun wrap(publications: List<ThemePublicationDisplay>): LegacyPageResponse<LegacyThemeDto> {
        val content = publications.map { it.toLegacy() }
        return LegacyPageResponse(content = content, totalCount = content.size)
    }

    private fun single(
        user: User,
        theme: TimetableThemeDisplay,
    ): LegacyThemeDto = library(user, listOf(theme)).single()
}

data class LegacyDiaryQuestionnaireRequest(
    val lectureId: Long,
    val dailyClassTypes: List<String>,
)

data class LegacyDiarySubmissionRequest(
    val lectureId: Long,
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
    val date: Instant,
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
                DiaryQuestionnaireRequest(
                    lectureId = body.lectureId,
                    dailyClassTypes = body.dailyClassTypes,
                ),
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
                        lectureId = it.lectureId?.toString(),
                        courseTitle = clientInfo.language.select(it.courseTitle, it.courseTitleEn),
                    )
                },
        )
    }

    @GetMapping("/target")
    fun getRandomTargetLecture(
        @V1CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Semester,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyDiaryTargetLectureDto {
        val target =
            diaryService.getDiaryTargetLecture(user.id!!, year, semester, emptyList())
                ?: throw SnuttException(ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND)
        return LegacyDiaryTargetLectureDto(
            lectureId = target.lectureId?.toString(),
            courseTitle = clientInfo.language.select(target.courseTitle, target.courseTitleEn),
        )
    }

    @GetMapping("/dailyClassTypes")
    fun getDailyClassTypes(
        @V1CurrentUser user: User,
    ): List<LegacyDiaryDailyClassTypeDto> =
        diaryService
            .getActiveDailyClassTypes()
            .map { LegacyDiaryDailyClassTypeDto(id = it.id!!.toString(), name = it.name) }

    @GetMapping("/my")
    fun getMySubmissions(
        @V1CurrentUser user: User,
    ): List<LegacyDiarySemesterSubmissionsDto> {
        val submissions = diaryService.getMySubmissions(user.id!!)
        val replies = diaryService.getSubmissionIdShortQuestionRepliesMap(submissions)
        return submissions
            .groupBy { it.year to it.semester }
            .map { (yearSemester, group) ->
                LegacyDiarySemesterSubmissionsDto(
                    year = yearSemester.first,
                    semester = yearSemester.second.value,
                    submissions =
                        group.map { submission ->
                            LegacyDiarySubmissionDto(
                                id = submission.id!!.toString(),
                                lectureId = submission.lectureId?.toString(),
                                date = checkNotNull(submission.createdAt),
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
    ): LegacyOkResponse {
        diaryService.submitDiary(
            user.id!!,
            DiarySubmissionRequest(
                lectureId = body.lectureId,
                dailyClassTypes = body.dailyClassTypes,
                questionAnswers = body.questionAnswers,
                comment = body.comment,
            ),
        )
        return LegacyOkResponse()
    }

    @DeleteMapping("/{submissionId}")
    fun removeDiarySubmission(
        @V1CurrentUser user: User,
        @PathVariable submissionId: Long,
    ): LegacyOkResponse {
        diaryService.removeSubmission(submissionId, user.id!!)
        return LegacyOkResponse()
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
        @PathVariable semester: Semester,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTagUpdateTimeResponse {
        val vocabulary =
            lectureVocabularyService.getVocabulary(year, semester, clientInfo.language)
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
        @PathVariable timetableId: Long,
    ): List<LegacyReminderDto> =
        timetableLectureReminderService
            .getReminders(user.id!!, timetableId)
            .map { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    @GetMapping("/{timetableLectureId}/reminder")
    fun getReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
    ): LegacyReminderDto =
        timetableLectureReminderService
            .getReminder(user.id!!, timetableId, timetableLectureId)
            .let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    @PutMapping("/{timetableLectureId}/reminder")
    fun modifyReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestBody body: LegacyReminderModifyRequest,
    ): LegacyReminderDto =
        timetableLectureReminderService
            .modifyReminder(user.id!!, timetableId, timetableLectureId, body.option)
            .let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    private fun legacyReminder(
        timetableLectureId: Long,
        courseTitle: String,
        option: TimetableLectureReminderOption,
    ) = LegacyReminderDto(
        timetableLectureId = timetableLectureId.toString(),
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
        @PathVariable lectureId: Long,
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
