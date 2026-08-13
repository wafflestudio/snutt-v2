package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyBookmarkLectureDto
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyEvSummary
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyLectureDto
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyTimetableDto
import com.wafflestudio.snutt.api.v1compat.snutt.dto.toLegacyLocalDateTimeString
import com.wafflestudio.snutt.api.v1compat.snutt.dto.toLegacyZonedDateTimeString
import com.wafflestudio.snutt.api.v2.bookmark.BookmarkLectureModifyRequest
import com.wafflestudio.snutt.api.v2.config.ConfigController
import com.wafflestudio.snutt.api.v2.feedback.FeedbackController
import com.wafflestudio.snutt.api.v2.feedback.FeedbackPostRequest
import com.wafflestudio.snutt.api.v2.friend.FriendController
import com.wafflestudio.snutt.api.v2.friend.FriendRequest
import com.wafflestudio.snutt.api.v2.friend.FriendResponse
import com.wafflestudio.snutt.api.v2.friend.UpdateFriendDisplayNameRequest
import com.wafflestudio.snutt.api.v2.notification.NotificationController
import com.wafflestudio.snutt.api.v2.popup.PopupController
import com.wafflestudio.snutt.api.v2.pushpreference.PushPreferenceController
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceDto
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// v1은 목록을 {content, totalCount}로 감싼다 (../snutt common/dto/ListResponse.kt)
private fun <T> listResponse(content: List<T>) = linkedMapOf("content" to content, "totalCount" to content.size)

private fun FriendResponse.toLegacy() =
    linkedMapOf(
        "id" to id,
        "userId" to userId,
        "displayName" to displayName,
        "nickname" to linkedMapOf("nickname" to nickname, "tag" to nicknameTag),
        "createdAt" to createdAt.toLegacyLocalDateTimeString(),
    )

@RestController
@RequestMapping("/v1/friends", "/friends")
class V1CompatFriendController(
    private val delegate: FriendController,
    private val friendService: com.wafflestudio.snutt.core.domain.friend.service.FriendService,
    private val timetableService: com.wafflestudio.snutt.core.domain.timetable.service.TimetableService,
    private val userRepository: com.wafflestudio.snutt.core.domain.user.repository.UserRepository,
) {
    @GetMapping("")
    fun getFriends(
        @CurrentUser user: User,
        @RequestParam state: String,
    ) = listResponse(delegate.getFriends(user, state).map { it.toLegacy() })

    @PostMapping("")
    fun requestFriend(
        @CurrentUser user: User,
        @RequestBody body: FriendRequest,
    ) = delegate.requestFriend(user, body)

    @PostMapping("/{friendId}/accept")
    fun acceptFriend(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ) = delegate.acceptFriend(user, friendId)

    @PostMapping("/{friendId}/decline")
    fun declineFriend(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ) = delegate.declineFriend(user, friendId)

    @PatchMapping("/{friendId}/display-name")
    fun updateFriendDisplayName(
        @CurrentUser user: User,
        @PathVariable friendId: String,
        @RequestBody body: UpdateFriendDisplayNameRequest,
    ) = delegate.updateFriendDisplayName(user, friendId, body)

    @DeleteMapping("/{friendId}")
    fun breakFriend(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ) = delegate.breakFriend(user, friendId)

    @GetMapping("/generate-link")
    fun generateFriendLink(
        @CurrentUser user: User,
    ) = delegate.generateFriendLink(user)

    @PostMapping("/accept-link/{requestToken}")
    fun acceptFriendByLink(
        @CurrentUser user: User,
        @PathVariable requestToken: String,
    ) = delegate.acceptFriendByLink(user, requestToken).toLegacy()

    @GetMapping("/{friendId}/primary-table")
    fun getPrimaryTable(
        @CurrentUser user: User,
        @PathVariable friendId: String,
        @RequestParam year: Int,
        @RequestParam semester: Int,
    ): LegacyTimetableDto {
        val friend =
            friendService.get(friendId)
                ?: throw com.wafflestudio.snutt.core.common.error.SnuttException(
                    com.wafflestudio.snutt.core.common.error.ErrorType.FRIEND_NOT_FOUND,
                )
        if (!friend.isAccepted ||
            !friend.includes(user.id!!)
        ) {
            throw com.wafflestudio.snutt.core.common.error.SnuttException(
                com.wafflestudio.snutt.core.common.error.ErrorType.FRIEND_NOT_FOUND,
            )
        }
        val partnerId = friend.getPartnerUserId(user.id!!)
        val timetable =
            timetableService.getUserPrimaryTable(
                partnerId,
                year,
                com.wafflestudio.snutt.core.common.enums.Semester
                    .fromValue(semester),
            )
        val display = timetableService.getTimetableDisplay(partnerId, timetable.externalId)
        val partner = userRepository.findById(partnerId).orElse(null)
        return LegacyTimetableDto(
            timetable = timetable,
            userId = partner?.externalId.orEmpty(),
            display = display,
            evLectureIds = emptyMap(),
        )
    }

    @GetMapping("/{friendId}/coursebooks", "/{friendId}/registered-course-books")
    fun getCoursebooks(
        @CurrentUser user: User,
        @PathVariable friendId: String,
    ) = delegate.getCoursebooks(user, friendId)
}

@RestController
@RequestMapping("/v1/notification", "/notification")
class V1CompatNotificationController(
    private val delegate: NotificationController,
) {
    @GetMapping("")
    fun getNotifications(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "0") offset: Long,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") explicit: Int,
    ) = delegate
        .getNotifications(user, offset, limit, explicit)
        .map {
            linkedMapOf(
                "id" to it.id,
                "user_id" to it.userId,
                "title" to it.title,
                "message" to it.message,
                "type" to it.type.value,
                "deeplink" to it.deeplink,
                "created_at" to it.createdAt.toLegacyZonedDateTimeString(),
            )
        }

    @GetMapping("/count")
    fun getUnreadCount(
        @CurrentUser user: User,
    ) = delegate.getUnreadCount(user)
}

@RestController
@RequestMapping("/v1/bookmarks", "/bookmarks")
class V1CompatBookmarkController(
    private val bookmarkService: com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkService,
    private val evaluationService: EvaluationService,
) {
    @GetMapping("")
    fun getBookmarks(
        @CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Int,
    ): Map<String, Any?> {
        val display =
            bookmarkService.getBookmark(
                user.id!!,
                year,
                com.wafflestudio.snutt.core.common.enums.Semester
                    .fromValue(semester),
            )
        val summaries = evaluationService.findSummariesByLectureIds(display.lectures.mapNotNull { it.id })
        return linkedMapOf(
            "year" to year,
            "semester" to semester,
            "lectures" to
                display.lectures.map { lecture ->
                    val evSummary =
                        lecture.id
                            ?.let(summaries::get)
                            ?.let { lecture.courseId?.let { courseId -> LegacyEvSummary(courseId, it.avgRating, it.evalCount) } }
                    LegacyBookmarkLectureDto(lecture, evSummary)
                },
        )
    }

    @GetMapping("/lectures/{lectureId}/state")
    fun existsBookmarkLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Map<String, Any?> = mapOf("exists" to bookmarkService.existsBookmarkLecture(user.id!!, lectureId))

    @PostMapping("/lecture")
    fun addLecture(
        @CurrentUser user: User,
        @RequestBody body: BookmarkLectureModifyRequest,
    ) = bookmarkService.addLecture(user.id!!, body.lectureId)

    @DeleteMapping("/lecture")
    fun deleteLecture(
        @CurrentUser user: User,
        @RequestBody body: BookmarkLectureModifyRequest,
    ) = bookmarkService.deleteLecture(user.id!!, body.lectureId)
}

@RestController
@RequestMapping("/v1/vacancy-notifications", "/vacancy-notifications")
class V1CompatVacancyNotificationController(
    private val vacancyNotificationService: com.wafflestudio.snutt.core.domain.vacancy.service.VacancyNotificationService,
    private val evaluationService: EvaluationService,
) {
    @GetMapping("/lectures")
    fun getLectures(
        @CurrentUser user: User,
    ): Map<String, Any?> {
        val lectures = vacancyNotificationService.getVacancyNotificationLectures(user.id!!)
        val summaries = evaluationService.findSummariesByLectureIds(lectures.mapNotNull { it.id })
        return mapOf(
            "lectures" to
                lectures.map { lecture ->
                    val evSummary =
                        lecture.id
                            ?.let(summaries::get)
                            ?.let { lecture.courseId?.let { courseId -> LegacyEvSummary(courseId, it.avgRating, it.evalCount) } }
                    LegacyLectureDto(lecture, evSummary)
                },
        )
    }

    @GetMapping("/lectures/{lectureId}/state")
    fun exists(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Map<String, Any?> = mapOf("exists" to vacancyNotificationService.existsVacancyNotification(user.id!!, lectureId))

    @PostMapping("/lectures/{lectureId}")
    fun add(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ) = vacancyNotificationService.addVacancyNotification(user.id!!, lectureId)

    @DeleteMapping("/lectures/{lectureId}")
    fun delete(
        @CurrentUser user: User,
        @PathVariable lectureId: String,
    ) = vacancyNotificationService.deleteVacancyNotification(user.id!!, lectureId)
}

@RestController
@RequestMapping("/v1/popups", "/popups")
class V1CompatPopupController(
    private val delegate: PopupController,
) {
    // v1은 key/image_url/hidden_days 별칭을 함께 내려준다
    @GetMapping("")
    fun getPopups(
        @RequestAttribute clientInfo: ClientInfo,
    ) = delegate.getPopups(clientInfo).map {
        linkedMapOf(
            "id" to it.id,
            "key" to it.popupKey,
            "imageUri" to it.imageUri,
            "image_url" to it.imageUri,
            "linkUrl" to it.linkUrl,
            "hiddenDays" to it.hiddenDays,
            "hidden_days" to it.hiddenDays,
        )
    }
}

@RestController
@RequestMapping("/v1/configs", "/configs")
class V1CompatConfigController(
    private val delegate: ConfigController,
) {
    @GetMapping("")
    fun getConfigs(
        @RequestAttribute clientInfo: ClientInfo,
    ) = delegate.getConfigs(clientInfo)
}

@RestController
@RequestMapping("/v1/push/preferences", "/push/preferences")
class V1CompatPushPreferenceController(
    private val delegate: PushPreferenceController,
) {
    @GetMapping("")
    fun getPushPreferences(
        @CurrentUser user: User,
    ) = delegate.getPushPreferences(user)

    @PostMapping("")
    fun savePushPreferences(
        @CurrentUser user: User,
        @RequestBody dto: PushPreferenceDto,
    ) = delegate.savePushPreferences(user, dto)
}

@RestController
@RequestMapping("/v1/feedback", "/feedback")
class V1CompatFeedbackController(
    private val delegate: FeedbackController,
) {
    @PostMapping("")
    fun postFeedback(
        @RequestBody body: FeedbackPostRequest,
        @RequestAttribute clientInfo: ClientInfo,
    ) = delegate.postFeedback(body, clientInfo)
}
