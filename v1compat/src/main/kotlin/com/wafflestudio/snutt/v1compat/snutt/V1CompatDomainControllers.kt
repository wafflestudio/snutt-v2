package com.wafflestudio.snutt.v1compat.snutt

import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.storage.StorageUriResolver
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkService
import com.wafflestudio.snutt.core.domain.clientconfig.service.ClientConfigService
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.feedback.service.FeedbackService
import com.wafflestudio.snutt.core.domain.friend.model.Friend
import com.wafflestudio.snutt.core.domain.friend.service.FriendService
import com.wafflestudio.snutt.core.domain.friend.service.FriendState
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.notification.service.NotificationService
import com.wafflestudio.snutt.core.domain.popup.service.PopupService
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceDto
import com.wafflestudio.snutt.core.domain.pushpreference.service.PushPreferenceService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.UserService
import com.wafflestudio.snutt.core.domain.vacancy.service.VacancyNotificationService
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyBookmarkLectureDto
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyLectureDto
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyTimetableDto
import com.wafflestudio.snutt.v1compat.snutt.dto.toLegacyEvSummary
import com.wafflestudio.snutt.v1compat.snutt.dto.toLegacyLocalDateTimeString
import com.wafflestudio.snutt.v1compat.snutt.dto.toLegacyZonedDateTimeString
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private fun <T> listResponse(content: List<T>) = linkedMapOf("content" to content, "totalCount" to content.size)

data class LegacyFriendRequest(
    val nickname: String,
)

data class LegacyFriendDisplayNameRequest(
    val displayName: String,
)

data class LegacyBookmarkLectureRequest(
    val lectureId: String,
)

data class LegacyFeedbackRequest(
    val email: String?,
    val message: String,
)

private fun legacyFriend(
    friend: Friend,
    partner: User,
    myUserId: Long,
) = linkedMapOf(
    "id" to friend.externalId,
    "userId" to partner.externalId,
    "displayName" to friend.getPartnerDisplayName(myUserId),
    "nickname" to
        linkedMapOf(
            "nickname" to partner.nicknameWithoutTag,
            "tag" to partner.nicknameTag,
        ),
    "createdAt" to checkNotNull(friend.createdAt).toEpochMilli().toLegacyLocalDateTimeString(),
)

@RestController
@RequestMapping("/v1/friends")
class V1CompatFriendController(
    private val friendService: FriendService,
    private val timetableService: TimetableService,
    private val userService: UserService,
) {
    @GetMapping("")
    fun getFriends(
        @V1CurrentUser user: User,
        @RequestParam state: String,
    ): Map<String, Any?> {
        val friendState =
            FriendState.entries.firstOrNull { it.name == state.uppercase() }
                ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        return listResponse(
            friendService.getMyFriends(user.id!!, friendState).map { (friend, partner) ->
                legacyFriend(friend, partner, user.id!!)
            },
        )
    }

    @PostMapping("")
    fun requestFriend(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyFriendRequest,
    ) {
        friendService.requestFriend(user.id!!, body.nickname)
    }

    @PostMapping("/{friendId}/accept")
    fun acceptFriend(
        @V1CurrentUser user: User,
        @PathVariable friendId: String,
    ) {
        friendService.acceptFriend(friendId, user.id!!)
    }

    @PostMapping("/{friendId}/decline")
    fun declineFriend(
        @V1CurrentUser user: User,
        @PathVariable friendId: String,
    ) {
        friendService.declineFriend(friendId, user.id!!)
    }

    @PatchMapping("/{friendId}/display-name")
    fun updateFriendDisplayName(
        @V1CurrentUser user: User,
        @PathVariable friendId: String,
        @RequestBody body: LegacyFriendDisplayNameRequest,
    ) {
        friendService.updateFriendDisplayName(user.id!!, friendId, body.displayName)
    }

    @DeleteMapping("/{friendId}")
    fun breakFriend(
        @V1CurrentUser user: User,
        @PathVariable friendId: String,
    ) {
        friendService.breakFriend(friendId, user.id!!)
    }

    @GetMapping("/generate-link")
    fun generateFriendLink(
        @V1CurrentUser user: User,
    ): Map<String, Any?> = mapOf("requestToken" to friendService.generateFriendRequestLink(user.id!!))

    @PostMapping("/accept-link/{requestToken}")
    fun acceptFriendByLink(
        @V1CurrentUser user: User,
        @PathVariable requestToken: String,
    ): Map<String, Any?> {
        val (friend, partner) = friendService.acceptFriendByLink(user.id!!, requestToken)
        return legacyFriend(friend, partner, user.id!!)
    }

    @GetMapping("/{friendId}/primary-table")
    fun getPrimaryTable(
        @V1CurrentUser user: User,
        @PathVariable friendId: String,
        @RequestParam year: Int,
        @RequestParam semester: Int,
    ): LegacyTimetableDto {
        val partnerId = acceptedFriend(user.id!!, friendId).getPartnerUserId(user.id!!)
        val timetable = timetableService.getUserPrimaryTable(partnerId, year, Semester.fromValue(semester))
        val display = timetableService.getTimetableDisplay(partnerId, timetable.externalId)
        val partnerExternalId = userService.getExternalIds(listOf(partnerId))[partnerId]
        return LegacyTimetableDto(
            timetable = timetable,
            userId = partnerExternalId.orEmpty(),
            display = display,
            evLectureIds = emptyMap(),
        )
    }

    @GetMapping("/{friendId}/coursebooks", "/{friendId}/registered-course-books")
    fun getCoursebooks(
        @V1CurrentUser user: User,
        @PathVariable friendId: String,
    ): List<Map<String, Any?>> {
        val partnerId = acceptedFriend(user.id!!, friendId).getPartnerUserId(user.id!!)
        return timetableService
            .getCoursebooksWithPrimaryTable(partnerId)
            .map { linkedMapOf("year" to it.first, "semester" to it.second.value) }
    }

    private fun acceptedFriend(
        userId: Long,
        friendExternalId: String,
    ): Friend {
        val friend = friendService.get(friendExternalId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.isAccepted || !friend.includes(userId)) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        return friend
    }
}

@RestController
@RequestMapping("/v1/notification")
class V1CompatNotificationController(
    private val notificationService: NotificationService,
    private val userService: UserService,
) {
    @GetMapping("")
    fun getNotifications(
        @V1CurrentUser user: User,
        @RequestParam(defaultValue = "0") offset: Long,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") explicit: Int,
    ): List<Map<String, Any?>> {
        val notifications = notificationService.getNotifications(user, offset, limit, explicit > 0)
        val externalIdByUserId = userService.getExternalIds(notifications.mapNotNull { it.userId })
        return notifications.map {
            linkedMapOf(
                "id" to it.externalId,
                "user_id" to externalIdByUserId[it.userId],
                "title" to it.title,
                "message" to it.message,
                "type" to it.type.value,
                "deeplink" to it.deeplink,
                "created_at" to checkNotNull(it.createdAt).toEpochMilli().toLegacyZonedDateTimeString(),
            )
        }
    }

    @GetMapping("/count")
    fun getUnreadCount(
        @V1CurrentUser user: User,
    ): Map<String, Any?> = mapOf("count" to notificationService.getUnreadCount(user))
}

@RestController
@RequestMapping("/v1/bookmarks")
class V1CompatBookmarkController(
    private val bookmarkService: BookmarkService,
    private val evaluationService: EvaluationService,
    private val lectureService: LectureService,
) {
    @GetMapping("")
    fun getBookmarks(
        @V1CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Int,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): Map<String, Any?> {
        val display = bookmarkService.getBookmark(user.id!!, year, Semester.fromValue(semester))
        val summaries = evaluationService.findSummariesByLectureIds(display.lectures.mapNotNull { it.id })
        val classTimesMap = lectureService.classTimesByLectureId(display.lectures.mapNotNull { it.id })
        return linkedMapOf(
            "year" to year,
            "semester" to semester,
            "lectures" to
                display.lectures.map { lecture ->
                    LegacyBookmarkLectureDto(
                        lecture,
                        classTimesMap[lecture.id].orEmpty(),
                        clientInfo.language,
                        summaries[lecture.id]?.toLegacyEvSummary(lecture.courseId),
                    )
                },
        )
    }

    @GetMapping("/lectures/{lectureId}/state")
    fun existsBookmarkLecture(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Map<String, Any?> = mapOf("exists" to bookmarkService.existsBookmarkLecture(user.id!!, lectureId))

    @PostMapping("/lecture")
    fun addLecture(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyBookmarkLectureRequest,
    ) {
        bookmarkService.addLecture(user.id!!, body.lectureId)
    }

    @DeleteMapping("/lecture")
    fun deleteLecture(
        @V1CurrentUser user: User,
        @RequestBody body: LegacyBookmarkLectureRequest,
    ) {
        bookmarkService.deleteLecture(user.id!!, body.lectureId)
    }
}

@RestController
@RequestMapping("/v1/vacancy-notifications")
class V1CompatVacancyNotificationController(
    private val vacancyNotificationService: VacancyNotificationService,
    private val evaluationService: EvaluationService,
    private val lectureService: LectureService,
) {
    @GetMapping("/lectures")
    fun getLectures(
        @V1CurrentUser user: User,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): Map<String, Any?> {
        val lectures = vacancyNotificationService.getVacancyNotificationLectures(user.id!!)
        val summaries = evaluationService.findSummariesByLectureIds(lectures.mapNotNull { it.id })
        val classTimesMap = lectureService.classTimesByLectureId(lectures.mapNotNull { it.id })
        return mapOf(
            "lectures" to
                lectures.map { lecture ->
                    LegacyLectureDto(
                        lecture,
                        classTimesMap[lecture.id].orEmpty(),
                        clientInfo.language,
                        summaries[lecture.id]?.toLegacyEvSummary(lecture.courseId),
                    )
                },
        )
    }

    @GetMapping("/lectures/{lectureId}/state")
    fun exists(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
    ): Map<String, Any?> = mapOf("exists" to vacancyNotificationService.existsVacancyNotification(user.id!!, lectureId))

    @PostMapping("/lectures/{lectureId}")
    fun add(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
    ) {
        vacancyNotificationService.addVacancyNotification(user.id!!, lectureId)
    }

    @DeleteMapping("/lectures/{lectureId}")
    fun delete(
        @V1CurrentUser user: User,
        @PathVariable lectureId: String,
    ) {
        vacancyNotificationService.deleteVacancyNotification(user.id!!, lectureId)
    }
}

@RestController
@RequestMapping("/v1/popups")
class V1CompatPopupController(
    private val popupService: PopupService,
    private val storageUriResolver: StorageUriResolver,
) {
    @GetMapping("")
    fun getPopups(
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): List<Map<String, Any?>> =
        popupService.getPopups().map {
            val imageUri = storageUriResolver.resolve(it.imageOriginUri)
            linkedMapOf(
                "id" to it.externalId,
                "key" to it.popupKey,
                "imageUri" to imageUri,
                "image_url" to imageUri,
                "linkUrl" to it.linkUrl,
                "hiddenDays" to it.hiddenDays,
                "hidden_days" to it.hiddenDays,
            )
        }
}

@RestController
@RequestMapping("/v1/configs")
class V1CompatConfigController(
    private val configService: ClientConfigService,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping("")
    fun getConfigs(
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): Map<String, JsonNode> {
        val appVersion = clientInfo.appVersion ?: return emptyMap()
        return configService
            .getConfigs(clientInfo.osType.lowercase(), appVersion)
            .associate { it.name to jsonMapper.readTree(it.value) }
    }
}

@RestController
@RequestMapping("/v1/push/preferences")
class V1CompatPushPreferenceController(
    private val pushPreferenceService: PushPreferenceService,
) {
    @GetMapping("")
    fun getPushPreferences(
        @V1CurrentUser user: User,
    ): PushPreferenceDto = pushPreferenceService.getPushPreferences(user)

    @PostMapping("")
    fun savePushPreferences(
        @V1CurrentUser user: User,
        @RequestBody dto: PushPreferenceDto,
    ): PushPreferenceDto {
        pushPreferenceService.savePushPreferences(user, dto)
        return pushPreferenceService.getPushPreferences(user)
    }
}

@RestController
@RequestMapping("/v1/feedback")
class V1CompatFeedbackController(
    private val feedbackService: FeedbackService,
) {
    @PostMapping("")
    fun postFeedback(
        @RequestBody body: LegacyFeedbackRequest,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ) {
        feedbackService.postFeedback(
            email = body.email.orEmpty(),
            message = body.message,
            osType = clientInfo.osType,
            osVersion = clientInfo.osVersion,
            appVersion = clientInfo.appVersion ?: "Unknown",
            deviceModel = clientInfo.deviceModel ?: "Unknown",
        )
    }
}
