package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.v1compat.snutt.dto.toLegacyLocalDateTime
import com.wafflestudio.snutt.api.v2.diary.DiaryQuestionnaireRequestDto
import com.wafflestudio.snutt.api.v2.diary.DiarySubmissionRequestDto
import com.wafflestudio.snutt.api.v2.theme.ThemeAddRequest
import com.wafflestudio.snutt.api.v2.theme.ThemeController
import com.wafflestudio.snutt.api.v2.theme.ThemeDownloadRequest
import com.wafflestudio.snutt.api.v2.theme.ThemeModifyRequest
import com.wafflestudio.snutt.api.v2.theme.ThemePublishRequest
import com.wafflestudio.snutt.api.v2.theme.ThemeResponse
import com.wafflestudio.snutt.api.v2.timetable.TimetableLectureReminderController
import com.wafflestudio.snutt.api.v2.timetable.TimetableLectureReminderModifyRequest
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.tag.service.TagListService
import com.wafflestudio.snutt.core.domain.user.model.User
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

// v1 TimetableThemeDto: colors/publishInfo 이름과 basic 테마 번호를 쓴다
private fun ThemeResponse.toLegacy(
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

@RestController
@RequestMapping("/v1/themes", "/themes")
class V1CompatThemeController(
    private val delegate: ThemeController,
    private val timetableThemeRepository: com.wafflestudio.snutt.core.domain.theme.repository.TimetableThemeRepository,
    private val userRepository: com.wafflestudio.snutt.core.domain.user.repository.UserRepository,
) {
    // v1은 받아온 테마의 원본 정보(origin)를 함께 준다. 목록에서 1회씩 일괄 조회한다 (N+1 회피)
    private fun originMap(responses: List<ThemeResponse>): Map<String, Map<String, Any?>> {
        val downloaded = responses.filter { it.status == com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus.DOWNLOADED }
        if (downloaded.isEmpty()) return emptyMap()
        val themesByExternalId =
            timetableThemeRepository
                .findAllByExternalIdIn(downloaded.mapNotNull { it.id })
                .associateBy { it.externalId }
        val originThemes =
            timetableThemeRepository
                .findAllById(
                    themesByExternalId.values.mapNotNull { it.originThemeId },
                ).associateBy { it.id!! }
        val originAuthors = userRepository.findAllById(themesByExternalId.values.mapNotNull { it.originAuthorId }).associateBy { it.id!! }
        return themesByExternalId
            .mapNotNull { (externalId, theme) ->
                val originThemeExternalId = theme.originThemeId?.let(originThemes::get)?.externalId ?: return@mapNotNull null
                externalId to
                    linkedMapOf(
                        "originId" to originThemeExternalId,
                        "authorId" to theme.originAuthorId?.let(originAuthors::get)?.externalId,
                    )
            }.toMap()
    }

    @GetMapping("")
    fun getThemes(
        @CurrentUser user: User,
    ): List<Map<String, Any?>> {
        val themes = delegate.getThemes(user)
        val origins = originMap(themes)
        return themes.map { it.toLegacy(user.externalId, origins[it.id]) }
    }

    @GetMapping("/best")
    fun getBestThemes(
        @CurrentUser user: User,
        @RequestParam page: Int,
    ): Map<String, Any?> {
        val themes = delegate.getBestThemes(page)
        val origins = originMap(themes)
        val content = themes.map { it.toLegacy(user.externalId, origins[it.id]) }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    @GetMapping("/friends")
    fun getFriendsThemes(
        @CurrentUser user: User,
        @RequestParam page: Int,
    ): Map<String, Any?> {
        val themes = delegate.getFriendsThemes(user, page)
        val origins = originMap(themes)
        val content = themes.map { it.toLegacy(user.externalId, origins[it.id]) }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    // v1은 검색을 POST 본문으로 받는다
    @PostMapping("/search")
    fun searchThemes(
        @CurrentUser user: User,
        @RequestBody body: LegacyThemeSearchRequest,
    ): Map<String, Any?> {
        val themes = delegate.searchThemes(body.keyword)
        val origins = originMap(themes)
        val content = themes.map { it.toLegacy(user.externalId, origins[it.id]) }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    @GetMapping("/{themeId}")
    fun getTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ): Map<String, Any?> {
        val theme = delegate.getTheme(user, themeId)
        return theme.toLegacy(user.externalId, originMap(listOf(theme))[theme.id])
    }

    @PostMapping("")
    fun addTheme(
        @CurrentUser user: User,
        @RequestBody body: ThemeAddRequest,
    ) = delegate.addTheme(user, body).toLegacy(user.externalId, null)

    @PatchMapping("/{themeId}")
    fun modifyTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: ThemeModifyRequest,
    ): Map<String, Any?> {
        val theme = delegate.modifyTheme(user, themeId, body)
        return theme.toLegacy(user.externalId, originMap(listOf(theme))[theme.id])
    }

    @DeleteMapping("/{themeId}")
    fun deleteTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) = delegate.deleteTheme(user, themeId)

    @PostMapping("/{themeId}/publish")
    fun publishTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: LegacyThemePublishRequest,
    ): Map<String, Any?> {
        delegate.publishTheme(
            user,
            themeId,
            ThemePublishRequest(publishName = body.publishName, authorAnonymous = body.isAnonymous),
        )
        return mapOf("message" to "ok")
    }

    // v1의 공유 해제 경로는 /publish 이다
    @DeleteMapping("/{themeId}/publish")
    fun deletePublishedTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) = delegate.deletePublishedTheme(user, themeId)

    @PostMapping("/{themeId}/download")
    fun downloadTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
        @RequestBody body: ThemeDownloadRequest,
    ): Map<String, Any?> {
        val theme = delegate.downloadTheme(user, themeId, body)
        return theme.toLegacy(user.externalId, originMap(listOf(theme))[theme.id])
    }

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) = delegate.copyTheme(user, themeId).toLegacy(user.externalId, null)

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ): Map<String, Any?> {
        val theme = delegate.setDefault(user, themeId)
        return theme.toLegacy(user.externalId, originMap(listOf(theme))[theme.id])
    }

    @DeleteMapping("/{themeId}/default")
    fun unsetDefault(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) = delegate.unsetDefault(user, themeId).toLegacy(user.externalId, null)

    @PostMapping("/basic/{basicThemeTypeValue}/default")
    fun setBasicDefault(
        @CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ) = delegate.setBasicThemeDefault(user, basicThemeTypeValue).toLegacy(user.externalId, null)

    @DeleteMapping("/basic/{basicThemeTypeValue}/default")
    fun unsetBasicDefault(
        @CurrentUser user: User,
        @PathVariable basicThemeTypeValue: Int,
    ) = delegate.unsetBasicThemeDefault(user, basicThemeTypeValue).toLegacy(user.externalId, null)
}

@RestController
@RequestMapping("/v1/diary", "/diary")
class V1CompatDiaryController(
    private val diaryService: com.wafflestudio.snutt.core.domain.diary.service.DiaryService,
    private val lectureRepository: com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository,
) {
    // 질문 id는 재채번으로 Long이 되었다 (구 클라이언트의 ObjectId 문자열과 단절 — PLAN.md §8 id 변경)
    @PostMapping("/questionnaire")
    fun getQuestionnaire(
        @CurrentUser user: User,
        @RequestBody body: DiaryQuestionnaireRequestDto,
        @RequestAttribute clientInfo: com.wafflestudio.snutt.core.common.client.ClientInfo,
    ): Map<String, Any?> {
        val display =
            diaryService.generateQuestionnaire(
                user.id!!,
                com.wafflestudio.snutt.core.domain.diary.service.DiaryQuestionnaireRequest(
                    lectureId = body.lectureId,
                    dailyClassTypes = body.dailyClassTypes,
                ),
            )
        return linkedMapOf(
            "courseTitle" to clientInfo.language.select(display.courseTitle, display.courseTitleEn),
            "questions" to
                display.questions.map {
                    linkedMapOf("id" to it.id, "question" to it.question, "answers" to it.answerList)
                },
            "nextLecture" to
                display.nextLecture?.let {
                    linkedMapOf("lectureId" to it.lectureId, "courseTitle" to clientInfo.language.select(it.courseTitle, it.courseTitleEn))
                },
        )
    }

    @GetMapping("/target")
    fun getRandomTargetLecture(
        @CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Int,
        @RequestAttribute clientInfo: com.wafflestudio.snutt.core.common.client.ClientInfo,
    ): Map<String, Any?> {
        val target =
            diaryService.getDiaryTargetLecture(
                user.id!!,
                year,
                com.wafflestudio.snutt.core.common.enums.Semester
                    .fromValue(semester),
                emptyList(),
            )
                ?: throw com.wafflestudio.snutt.core.common.error.SnuttException(
                    com.wafflestudio.snutt.core.common.error.ErrorType.DIARY_TARGET_LECTURE_NOT_FOUND,
                )
        return linkedMapOf(
            "lectureId" to target.lectureId,
            "courseTitle" to clientInfo.language.select(target.courseTitle, target.courseTitleEn),
        )
    }

    // v1 경로는 camelCase 이다
    @GetMapping("/dailyClassTypes")
    fun getDailyClassTypes(
        @CurrentUser user: User,
    ): List<Map<String, Any?>> =
        diaryService
            .getAllDailyClassTypes()
            .map { linkedMapOf("id" to it.externalId, "name" to it.name) }

    // v1 DiarySubmissionSummaryDto: id/lectureId/date/courseTitle/shortQuestionReplies/comment
    @GetMapping("/my")
    fun getMySubmissions(
        @CurrentUser user: User,
    ): List<Map<String, Any?>> {
        val submissions = diaryService.getMySubmissions(user.id!!)
        val replies = diaryService.getSubmissionIdShortQuestionRepliesMap(submissions)
        val lectureExternalIds =
            lectureRepository
                .findAllById(
                    submissions.mapNotNull { it.lectureId },
                ).associate { it.id!! to it.externalId }
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
        @CurrentUser user: User,
        @RequestBody body: DiarySubmissionRequestDto,
    ) = diaryService.submitDiary(
        user.id!!,
        com.wafflestudio.snutt.core.domain.diary.service.DiarySubmissionRequest(
            body.lectureId,
            body.dailyClassTypes,
            body.questionAnswers,
            body.comment,
        ),
    )

    @DeleteMapping("/{submissionId}")
    fun removeDiarySubmission(
        @CurrentUser user: User,
        @PathVariable submissionId: String,
    ) = diaryService.removeSubmission(submissionId, user.id!!)
}

@RestController
@RequestMapping("/v1/tags", "/tags")
class V1CompatTagUpdateTimeController(
    private val tagListService: TagListService,
) {
    // 태그 목록의 갱신 시각만 따로 받아가는 경로
    @GetMapping("/{year}/{semester}/update_time")
    fun getTagListUpdateTime(
        @PathVariable year: Int,
        @PathVariable semester: Int,
    ): Map<String, Any?> {
        val tagList = tagListService.getTagList(year, Semester.fromValue(semester))
        return mapOf("updated_at" to checkNotNull(tagList.updatedAt).toEpochMilli())
    }
}

// v1의 리마인더 경로는 /tables/{id}/lecture/... 이다
@RestController
@RequestMapping("/v1/tables/{timetableId}/lecture", "/tables/{timetableId}/lecture")
class V1CompatReminderController(
    private val delegate: TimetableLectureReminderController,
) {
    @GetMapping("/reminders")
    fun getReminders(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ) = delegate.getReminders(user, timetableId)

    @GetMapping("/{timetableLectureId}/reminder")
    fun getReminder(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
    ) = delegate.getReminder(user, timetableId, timetableLectureId)

    @PutMapping("/{timetableLectureId}/reminder")
    fun modifyReminder(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
        @RequestBody body: TimetableLectureReminderModifyRequest,
    ) = delegate.modifyReminder(user, timetableId, timetableLectureId, body)
}

// v1의 강의평 요약은 인증 없이 열려 있다 (../snutt EvController)
@RestController
@RequestMapping("/v1/ev", "/ev")
class V1CompatEvSummaryController(
    private val evaluationService: com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService,
) {
    @com.wafflestudio.snutt.api.auth.Public
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
