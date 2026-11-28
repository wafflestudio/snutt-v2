package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.CurrentClient
import com.wafflestudio.snutt.core.common.client.OsType
import com.wafflestudio.snutt.core.common.client.compareAppVersions
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.model.V1IdRepository
import com.wafflestudio.snutt.core.common.model.V1IdTable
import com.wafflestudio.snutt.core.domain.diary.model.QuestionAnswer
import com.wafflestudio.snutt.core.domain.diary.service.DiaryQuestionnaireRequest
import com.wafflestudio.snutt.core.domain.diary.service.DiaryService
import com.wafflestudio.snutt.core.domain.diary.service.DiarySubmissionRequest
import com.wafflestudio.snutt.core.domain.evaluation.repository.CourseRepository
import com.wafflestudio.snutt.core.domain.friend.repository.FriendRepository
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.model.PublishedTheme
import com.wafflestudio.snutt.core.domain.theme.model.ThemeKind
import com.wafflestudio.snutt.core.domain.theme.repository.PublishedThemeRepository
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyColorSetDto
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyEvSummary
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyOkResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyPageResponse
import com.wafflestudio.snutt.v1compat.snutt.dto.legacyBuiltinCode
import com.wafflestudio.snutt.v1compat.snutt.dto.legacyThemeValue
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    val authorId: String,
)

data class LegacyThemePublishInfoDto(
    val publishName: String,
    val authorName: String?,
    val downloads: Long,
)

private const val LEGACY_THEME_PAGE_SIZE = 10
private const val ANDROID_LEGACY_DIARY_DATE_MAX_VERSION = "3.12.4"

private fun TimetableThemeDisplay.toLegacy(
    userExternalId: String,
    origin: LegacyThemeOriginDto?,
    publishInfo: LegacyThemePublishInfoDto? = null,
) = LegacyThemeDto(
    id = if (kind == ThemeKind.BUILTIN) null else idV1,
    userId = userExternalId,
    theme = legacyThemeValue(builtinCode),
    name = name,
    colors = colors.takeUnless { kind == ThemeKind.BUILTIN }?.map { it.toLegacyColor() },
    isDefault = isDefault,
    isCustom = kind != ThemeKind.BUILTIN,
    origin = origin,
    status =
        when (kind) {
            ThemeKind.BUILTIN -> LegacyThemeStatus.BASIC
            ThemeKind.DOWNLOADED -> LegacyThemeStatus.DOWNLOADED
            ThemeKind.CUSTOM -> if (publishInfo == null) LegacyThemeStatus.PRIVATE else LegacyThemeStatus.PUBLISHED
        },
    publishInfo = publishInfo,
)

private fun PublishedTheme.toLegacy(authors: Map<Long, User>) =
    LegacyThemeDto(
        id = idV1,
        userId = authors[authorId]?.idV1.orEmpty(),
        theme = 0,
        name = name,
        colors = colors.map { it.toLegacyColor() },
        isDefault = false,
        isCustom = true,
        origin = null,
        status = LegacyThemeStatus.PUBLISHED,
        publishInfo =
            LegacyThemePublishInfoDto(
                publishName = name,
                authorName = if (authorAnonymous) "익명" else authors[authorId]?.nickname,
                downloads = downloadCount,
            ),
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
    private val v1Ids: V1IdRepository,
    private val timetableThemeService: TimetableThemeService,
    private val publishedThemeRepository: PublishedThemeRepository,
    private val legacyPublishedThemeRepository: LegacyPublishedThemeRepository,
    private val friendRepository: FriendRepository,
    private val userRepository: UserRepository,
) {
    private fun library(
        user: User,
        themes: List<TimetableThemeDisplay>,
    ): List<LegacyThemeDto> {
        val publicationIds = themes.mapNotNull { it.publicationId }
        val sources =
            if (publicationIds.isEmpty()) {
                emptyMap()
            } else {
                publishedThemeRepository.findAllById(publicationIds).associateBy { it.id!! }
            }
        val sourceAuthors = v1Ids.v1Ids(V1IdTable.USER, sources.values.mapNotNull { it.authorId })
        val ownPublications =
            legacyPublishedThemeRepository
                .findByAuthorIdAndListedTrueAndSourceThemeIdIsNotNullOrderById(user.id!!)
                .associateBy { it.sourceThemeId }
        return themes.map { theme ->
            val origin =
                theme.publicationId?.let { id ->
                    sources.getValue(id).let {
                        LegacyThemeOriginDto(
                            it.idV1,
                            it.authorId
                                ?.let { authorId ->
                                    sourceAuthors.getValue(authorId)
                                }.orEmpty(),
                        )
                    }
                }
            val publishInfo =
                ownPublications[theme.id]?.let {
                    LegacyThemePublishInfoDto(it.name, if (it.authorAnonymous) "익명" else user.nickname, it.downloadCount)
                }
            theme.toLegacy(user.idV1, origin, publishInfo)
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
    ): LegacyPageResponse<LegacyThemeDto> =
        wrap(legacyPublishedThemeRepository.findByListedTrueOrderByDownloadCountDescIdDesc(themePageable(page)))

    @GetMapping("/friends")
    fun getFriendsThemes(
        @V1CurrentUser user: User,
        @RequestParam page: Int,
    ): LegacyPageResponse<LegacyThemeDto> {
        val pageable = themePageable(page)
        val friendUserIds = friendRepository.findActiveByUserId(user.id!!).map { it.getPartnerUserId(user.id!!) }
        if (friendUserIds.isEmpty()) return LegacyPageResponse(content = emptyList(), totalCount = 0)
        return wrap(legacyPublishedThemeRepository.findFriendsListed(friendUserIds, pageable))
    }

    @PostMapping("/search")
    fun searchThemes(
        @V1CurrentUser user: User,
        @RequestParam query: String,
    ): LegacyPageResponse<LegacyThemeDto> = wrap(legacyPublishedThemeRepository.searchListed(query))

    @GetMapping("/{themeId}")
    fun getTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = single(user, timetableThemeService.getTheme(user.id!!, v1Ids.id(V1IdTable.THEME, themeId)))

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
            ).toLegacy(user.idV1, null)

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemeModifyRequest,
    ): LegacyThemeDto =
        single(
            user,
            timetableThemeService.modifyTheme(
                user.id!!,
                v1Ids.id(V1IdTable.THEME, themeId),
                body.name,
                body.colors?.map(LegacyColorRequest::requireColorSet),
            ),
        )

    @DeleteMapping("/{themeId}")
    fun deleteTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ) {
        if (listedPublicationsOf(
                user,
                v1Ids.id(V1IdTable.THEME, themeId),
            ).isNotEmpty()
        ) {
            throw SnuttException(ErrorType.CANNOT_DELETE_PUBLISHED_THEME)
        }
        timetableThemeService.deleteTheme(user.id!!, v1Ids.id(V1IdTable.THEME, themeId))
    }

    @Transactional
    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemePublishRequest,
    ): LegacyOkResponse {
        val publication =
            timetableThemeService.publishTheme(
                user.id!!,
                v1Ids.id(V1IdTable.THEME, themeId),
                body.publishName,
                body.isAnonymous,
            )
        listedPublicationsOf(user, v1Ids.id(V1IdTable.THEME, themeId)).filter { it.id != publication.id }.forEach {
            timetableThemeService.unpublishTheme(user.id!!, it.id!!)
        }
        return LegacyOkResponse()
    }

    @DeleteMapping("/{themeId}/publish")
    fun deletePublishedTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ) {
        val publications = listedPublicationsOf(user, v1Ids.id(V1IdTable.THEME, themeId))
        if (publications.isEmpty()) throw SnuttException(ErrorType.NOT_PUBLISHED_THEME)
        publications.forEach { timetableThemeService.unpublishTheme(user.id!!, it.id!!) }
    }

    @PostMapping("/{themeId}/download")
    fun downloadTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemeDownloadRequest,
    ): LegacyThemeDto = single(user, timetableThemeService.downloadTheme(user.id!!, v1Ids.id(V1IdTable.PUBLICATION, themeId)))

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = timetableThemeService.copyTheme(user.id!!, v1Ids.id(V1IdTable.THEME, themeId)).toLegacy(user.idV1, null)

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = single(user, timetableThemeService.setDefault(user.id!!, v1Ids.id(V1IdTable.THEME, themeId)))

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @V1CurrentUser user: User,
        @PathVariable themeId: String,
    ): LegacyThemeDto = timetableThemeService.unsetDefault(user.id!!, v1Ids.id(V1IdTable.THEME, themeId)).toLegacy(user.idV1, null)

    @PostMapping("/basic/{basicThemeTypeValue}/default")
    fun setBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): LegacyThemeDto {
        legacyBuiltinCode(basicThemeTypeValue)
        return defaultTheme(user).toLegacy(user.idV1, null)
    }

    @DeleteMapping("/basic/{basicThemeTypeValue}/default")
    fun unsetBasicDefault(
        @V1CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ): LegacyThemeDto {
        val builtinCode = legacyBuiltinCode(basicThemeTypeValue)
        val current = defaultTheme(user)
        if (current.kind != ThemeKind.BUILTIN || current.builtinCode != builtinCode) {
            throw SnuttException(ErrorType.NOT_DEFAULT_THEME)
        }
        return current.toLegacy(user.idV1, null)
    }

    private fun themePageable(page: Int): PageRequest {
        if (page <= 0 || page - 1 > Int.MAX_VALUE / LEGACY_THEME_PAGE_SIZE) {
            throw SnuttException(ErrorType.INVALID_PARAMETER)
        }
        return PageRequest.of(page - 1, LEGACY_THEME_PAGE_SIZE)
    }

    private fun listedPublicationsOf(
        user: User,
        themeId: Long,
    ): List<PublishedTheme> = legacyPublishedThemeRepository.findByAuthorIdAndSourceThemeIdAndListedTrue(user.id!!, themeId)

    private fun defaultTheme(user: User): TimetableThemeDisplay =
        timetableThemeService.getTheme(user.id!!, timetableThemeService.getDefaultThemeId(user.id!!))

    private fun wrap(publications: List<PublishedTheme>): LegacyPageResponse<LegacyThemeDto> {
        if (publications.isEmpty()) return LegacyPageResponse(content = emptyList(), totalCount = 0)
        val authorIds = publications.mapNotNull { it.authorId }.distinct()
        val authors = if (authorIds.isEmpty()) emptyMap() else userRepository.findAllById(authorIds).associateBy { it.id!! }
        val content = publications.map { it.toLegacy(authors) }
        return LegacyPageResponse(content = content, totalCount = content.size)
    }

    private fun single(
        user: User,
        theme: TimetableThemeDisplay,
    ): LegacyThemeDto = library(user, listOf(theme)).single()
}

data class LegacyDiaryQuestionnaireRequest(
    val lectureId: String,
    val dailyClassTypes: List<String>,
)

data class LegacyDiaryQuestionAnswer(
    val questionId: String,
    val answerIndex: Int,
)

data class LegacyDiarySubmissionRequest(
    val lectureId: String,
    val dailyClassTypes: List<String>,
    val questionAnswers: List<LegacyDiaryQuestionAnswer>,
    val comment: String,
)

data class LegacyDiaryQuestionnaireResponse(
    val courseTitle: String,
    val questions: List<LegacyDiaryQuestionDto>,
    val nextLecture: LegacyDiaryTargetLectureDto?,
)

data class LegacyDiaryQuestionDto(
    val id: String,
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
    val lectureId: String,
    val date: String,
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
    private val v1Ids: V1IdRepository,
    private val diaryService: DiaryService,
) {
    @PostMapping("/questionnaire")
    fun getQuestionnaire(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyDiaryQuestionnaireRequest,
        @CurrentClient clientInfo: ClientInfo,
    ): LegacyDiaryQuestionnaireResponse {
        val display =
            diaryService.generateQuestionnaire(
                user.id!!,
                DiaryQuestionnaireRequest(
                    lectureId = v1Ids.id(V1IdTable.LECTURE, body.lectureId),
                    dailyClassTypes = body.dailyClassTypes,
                ),
            )
        return LegacyDiaryQuestionnaireResponse(
            courseTitle = clientInfo.language.select(display.courseTitle, display.courseTitleEn),
            questions =
                display.questions.map {
                    LegacyDiaryQuestionDto(id = it.idV1, question = it.question, answers = it.answerList)
                },
            nextLecture =
                display.nextLecture?.let {
                    LegacyDiaryTargetLectureDto(
                        lectureId = it.lectureId?.let { id -> v1Ids.v1Id(V1IdTable.LECTURE, id) },
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
        @CurrentClient clientInfo: ClientInfo,
    ): LegacyDiaryTargetLectureDto {
        val target =
            diaryService.getDiaryTargetLecture(user.id!!, year, semester, emptyList())
                ?: throw SnuttException(ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND)
        return LegacyDiaryTargetLectureDto(
            lectureId = target.lectureId?.let { v1Ids.v1Id(V1IdTable.LECTURE, it) },
            courseTitle = clientInfo.language.select(target.courseTitle, target.courseTitleEn),
        )
    }

    @GetMapping("/dailyClassTypes")
    fun getDailyClassTypes(): List<LegacyDiaryDailyClassTypeDto> =
        diaryService
            .getActiveDailyClassTypes()
            .map { LegacyDiaryDailyClassTypeDto(id = it.idV1, name = it.name) }

    @GetMapping("/my")
    fun getMySubmissions(
        @V1CurrentUser user: User,
        @CurrentClient clientInfo: ClientInfo,
    ): List<LegacyDiarySemesterSubmissionsDto> {
        val appVersion = clientInfo.appVersion
        val dateFormatter =
            if (clientInfo.osType == OsType.ANDROID &&
                appVersion != null &&
                compareAppVersions(appVersion, ANDROID_LEGACY_DIARY_DATE_MAX_VERSION) <= 0
            ) {
                DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC)
            } else {
                DateTimeFormatter.ISO_INSTANT
            }
        val submissions = diaryService.getMySubmissions(user.id!!)
        val replies = diaryService.getSubmissionIdShortQuestionRepliesMap(submissions)
        val lectureIdsV1 = v1Ids.v1Ids(V1IdTable.LECTURE, submissions.mapNotNull { it.lectureId })
        return submissions
            .groupBy { it.year to it.semester }
            .map { (yearSemester, group) ->
                LegacyDiarySemesterSubmissionsDto(
                    year = yearSemester.first,
                    semester = yearSemester.second.value,
                    submissions =
                        group.map { submission ->
                            LegacyDiarySubmissionDto(
                                id = submission.idV1,
                                lectureId = submission.lectureId?.let { lectureIdsV1.getValue(it) }.orEmpty(),
                                date = dateFormatter.format(checkNotNull(submission.createdAt)),
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
                lectureId = v1Ids.id(V1IdTable.LECTURE, body.lectureId),
                dailyClassTypes = body.dailyClassTypes,
                questionAnswers =
                    body.questionAnswers.map {
                        QuestionAnswer(
                            v1Ids.id(V1IdTable.DIARY_QUESTION, it.questionId),
                            it.answerIndex,
                        )
                    },
                comment = body.comment,
            ),
        )
        return LegacyOkResponse()
    }

    @DeleteMapping("/{submissionId}")
    fun removeDiarySubmission(
        @V1CurrentUser user: User,
        @PathVariable submissionId: String,
    ): LegacyOkResponse {
        diaryService.removeSubmission(v1Ids.id(V1IdTable.DIARY_SUBMISSION, submissionId), user.id!!)
        return LegacyOkResponse()
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
    private val v1Ids: V1IdRepository,
    private val timetableLectureReminderService: TimetableLectureReminderService,
) {
    @GetMapping("/reminders")
    fun getReminders(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
    ): List<LegacyReminderDto> {
        val reminders = timetableLectureReminderService.getReminders(user.id!!, v1Ids.id(V1IdTable.TIMETABLE, timetableId))
        val ids = v1Ids.v1Ids(V1IdTable.TIMETABLE_LECTURE, reminders.map { it.timetableLectureId })
        return reminders.map { LegacyReminderDto(ids.getValue(it.timetableLectureId), it.courseTitle, it.option) }
    }

    @GetMapping("/{timetableLectureId}/reminder")
    fun getReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
    ): LegacyReminderDto =
        timetableLectureReminderService
            .getReminder(user.id!!, v1Ids.id(V1IdTable.TIMETABLE, timetableId), v1Ids.timetableLectureId(timetableId, timetableLectureId))
            .let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    @PutMapping("/{timetableLectureId}/reminder")
    fun modifyReminder(
        @V1CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
        @RequestBody body: LegacyReminderModifyRequest,
    ): LegacyReminderDto =
        timetableLectureReminderService
            .modifyReminder(
                user.id!!,
                v1Ids.id(V1IdTable.TIMETABLE, timetableId),
                v1Ids.timetableLectureId(timetableId, timetableLectureId),
                body.option,
            ).let { legacyReminder(it.timetableLectureId, it.courseTitle, it.option) }

    private fun legacyReminder(
        timetableLectureId: Long,
        courseTitle: String,
        option: TimetableLectureReminderOption,
    ) = LegacyReminderDto(
        timetableLectureId = v1Ids.v1Id(V1IdTable.TIMETABLE_LECTURE, timetableLectureId),
        courseTitle = courseTitle,
        option = option,
    )
}

@RestController
@RequestMapping("/v1/ev")
class V1CompatEvSummaryController(
    private val v1Ids: V1IdRepository,
    private val courseRepository: CourseRepository,
    private val lectureService: LectureService,
) {
    @V1Public
    @GetMapping("/lectures/{lectureId}/summary")
    fun getLectureEvaluationSummary(
        @PathVariable lectureId: String,
    ): LegacyEvSummary {
        val courseId =
            lectureService.get(v1Ids.id(V1IdTable.LECTURE, lectureId, ErrorType.EVALUATION_TARGET_NOT_FOUND)).courseId
                ?: throw SnuttException(ErrorType.EVALUATION_TARGET_NOT_FOUND)
        val course = courseRepository.findByIdOrNull(courseId)
        return LegacyEvSummary(
            evLectureId = courseId,
            avgRating = course?.avgRating,
            evaluationCount = course?.evalCount ?: 0L,
        )
    }
}
