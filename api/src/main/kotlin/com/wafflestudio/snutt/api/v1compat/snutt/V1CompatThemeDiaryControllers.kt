package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.v2.diary.DiaryController
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
    // v1은 받아온 테마의 원본 정보(origin)를 함께 준다
    private fun originOf(response: ThemeResponse): Map<String, Any?>? {
        if (response.status != com.wafflestudio.snutt.core.domain.theme.model.ThemeStatus.DOWNLOADED) return null
        val theme = response.id?.let { timetableThemeRepository.findByExternalId(it) } ?: return null
        val originThemeExternalId = theme.originThemeId?.let { timetableThemeRepository.findById(it).orElse(null)?.externalId }
        val originAuthorExternalId = theme.originAuthorId?.let { userRepository.findById(it).orElse(null)?.externalId }
        if (originThemeExternalId == null) return null
        return linkedMapOf("originId" to originThemeExternalId, "authorId" to originAuthorExternalId)
    }

    @GetMapping("")
    fun getThemes(
        @CurrentUser user: User,
    ) = delegate.getThemes(user).map { it.toLegacy(user.externalId, originOf(it)) }

    @GetMapping("/best")
    fun getBestThemes(
        @CurrentUser user: User,
        @RequestParam page: Int,
    ): Map<String, Any?> {
        val content = delegate.getBestThemes(page).map { it.toLegacy(user.externalId, originOf(it)) }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    @GetMapping("/friends")
    fun getFriendsThemes(
        @CurrentUser user: User,
        @RequestParam page: Int,
    ): Map<String, Any?> {
        val content = delegate.getFriendsThemes(user, page).map { it.toLegacy(user.externalId, originOf(it)) }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    // v1은 검색을 POST 본문으로 받는다
    @PostMapping("/search")
    fun searchThemes(
        @CurrentUser user: User,
        @RequestBody body: LegacyThemeSearchRequest,
    ): Map<String, Any?> {
        val content = delegate.searchThemes(body.keyword).map { it.toLegacy(user.externalId, originOf(it)) }
        return mapOf("content" to content, "totalCount" to content.size)
    }

    @GetMapping("/{themeId}")
    fun getTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) = delegate.getTheme(user, themeId).toLegacy(user.externalId, originOf(delegate.getTheme(user, themeId)))

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
    ) = delegate.modifyTheme(user, themeId, body).toLegacy(user.externalId, originOf(delegate.getTheme(user, themeId)))

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
    ) = delegate.downloadTheme(user, themeId, body).toLegacy(user.externalId, originOf(delegate.getTheme(user, themeId)))

    @PostMapping("/{themeId}/copy")
    fun copyTheme(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) = delegate.copyTheme(user, themeId).toLegacy(user.externalId, null)

    @PostMapping("/{themeId}/default")
    fun setDefault(
        @CurrentUser user: User,
        @PathVariable themeId: String,
    ) = delegate.setDefault(user, themeId).toLegacy(user.externalId, originOf(delegate.getTheme(user, themeId)))

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
    private val delegate: DiaryController,
) {
    @PostMapping("/questionnaire")
    fun getQuestionnaire(
        @CurrentUser user: User,
        @RequestBody body: DiaryQuestionnaireRequestDto,
    ) = delegate.getQuestionnaire(user, body)

    @GetMapping("/target")
    fun getRandomTargetLecture(
        @CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Int,
    ) = delegate.getRandomTargetLecture(user, year, semester)

    // v1 경로는 camelCase 이다
    @GetMapping("/dailyClassTypes")
    fun getDailyClassTypes(
        @CurrentUser user: User,
    ) = delegate.getDailyClassTypes(user)

    @GetMapping("/my")
    fun getMySubmissions(
        @CurrentUser user: User,
    ) = delegate.getMySubmissions(user)

    @PostMapping("")
    fun submitDiary(
        @CurrentUser user: User,
        @RequestBody body: DiarySubmissionRequestDto,
    ) = delegate.submitDiary(user, body)

    @DeleteMapping("/{submissionId}")
    fun removeDiarySubmission(
        @CurrentUser user: User,
        @PathVariable submissionId: String,
    ) = delegate.removeDiarySubmission(user, submissionId)
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
        val display = evaluationService.getEvaluationSummaryOfLecture(lectureId)
        val lecture = display.lecture
        return linkedMapOf(
            "evLectureId" to lecture.courseId,
            "avgRating" to display.averages?.avgRating,
            "evaluationCount" to
                (
                    lecture.courseId?.let {
                        evaluationService
                            .findSummariesByLectureIds(
                                listOf(lecture.id!!),
                            )[lecture.id!!]
                            ?.evalCount
                    } ?: 0L
                ),
        )
    }
}
