package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.wafflestudio.snutt.v1compat.auth.V1Public
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyBookmarkLectureDto
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyLectureDto
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyPageResponse
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

data class LegacyFriendRequest(
    val nickname: String,
)

data class LegacyFriendDisplayNameRequest(
    val displayName: String,
)

data class LegacyBookmarkLectureRequest(
    val lectureId: Long,
)

data class LegacyFeedbackRequest(
    val email: String?,
    val message: String,
)

data class LegacyFriendDto(
    val id: String,
    val userId: String,
    val displayName: String?,
    val nickname: LegacyFriendNicknameDto,
    val createdAt: String,
)

data class LegacyFriendNicknameDto(
    val nickname: String,
    val tag: Int?,
)

data class LegacyFriendLinkResponse(
    val requestToken: String,
)

data class LegacyFriendCoursebookDto(
    val year: Int,
    val semester: Int,
)

private fun legacyFriend(
    friend: Friend,
    partner: User,
    myUserId: Long,
) = LegacyFriendDto(
    id = friend.id!!.toString(),
    userId = partner.id!!.toString(),
    displayName = friend.getPartnerDisplayName(myUserId),
    nickname =
        LegacyFriendNicknameDto(
            nickname = partner.nicknameWithoutTag,
            tag = partner.nicknameTag,
        ),
    createdAt = checkNotNull(friend.createdAt).toEpochMilli().toLegacyLocalDateTimeString(),
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
    ): LegacyPageResponse<LegacyFriendDto> {
        val friendState =
            FriendState.entries.firstOrNull { it.name == state.uppercase() }
                ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
        val content =
            friendService.getMyFriends(user.id!!, friendState).map { (friend, partner) ->
                legacyFriend(friend, partner, user.id!!)
            }
        return LegacyPageResponse(content = content, totalCount = content.size)
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
        @PathVariable friendId: Long,
    ) {
        friendService.acceptFriend(friendId, user.id!!)
    }

    @PostMapping("/{friendId}/decline")
    fun declineFriend(
        @V1CurrentUser user: User,
        @PathVariable friendId: Long,
    ) {
        friendService.declineFriend(friendId, user.id!!)
    }

    @PatchMapping("/{friendId}/display-name")
    fun updateFriendDisplayName(
        @V1CurrentUser user: User,
        @PathVariable friendId: Long,
        @RequestBody body: LegacyFriendDisplayNameRequest,
    ) {
        friendService.updateFriendDisplayName(user.id!!, friendId, body.displayName)
    }

    @DeleteMapping("/{friendId}")
    fun breakFriend(
        @V1CurrentUser user: User,
        @PathVariable friendId: Long,
    ) {
        friendService.breakFriend(friendId, user.id!!)
    }

    @GetMapping("/generate-link")
    fun generateFriendLink(
        @V1CurrentUser user: User,
    ): LegacyFriendLinkResponse = LegacyFriendLinkResponse(requestToken = friendService.generateFriendRequestLink(user.id!!))

    @PostMapping("/accept-link/{requestToken}")
    fun acceptFriendByLink(
        @V1CurrentUser user: User,
        @PathVariable requestToken: String,
    ): LegacyFriendDto {
        val (friend, partner) = friendService.acceptFriendByLink(user.id!!, requestToken)
        return legacyFriend(friend, partner, user.id!!)
    }

    @GetMapping("/{friendId}/primary-table")
    fun getPrimaryTable(
        @V1CurrentUser user: User,
        @PathVariable friendId: Long,
        @RequestParam year: Int,
        @RequestParam semester: Int,
    ): LegacyTimetableDto {
        val partnerId = acceptedFriend(user.id!!, friendId).getPartnerUserId(user.id!!)
        val timetable = timetableService.getUserPrimaryTable(partnerId, year, Semester.fromValue(semester))
        val display = timetableService.getTimetableDisplay(partnerId, timetable.id!!)
        val partnerExternalId = partnerId.toString()
        return LegacyTimetableDto(
            timetable = timetable,
            userId = partnerExternalId,
            display = display,
            evLectureIds = emptyMap(),
        )
    }

    @GetMapping("/{friendId}/coursebooks", "/{friendId}/registered-course-books")
    fun getCoursebooks(
        @V1CurrentUser user: User,
        @PathVariable friendId: Long,
    ): List<LegacyFriendCoursebookDto> {
        val partnerId = acceptedFriend(user.id!!, friendId).getPartnerUserId(user.id!!)
        return timetableService
            .getCoursebooksWithPrimaryTable(partnerId)
            .map { LegacyFriendCoursebookDto(year = it.first, semester = it.second.value) }
    }

    private fun acceptedFriend(
        userId: Long,
        friendId: Long,
    ): Friend {
        val friend = friendService.get(friendId) ?: throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        if (!friend.isAccepted || !friend.includes(userId)) throw SnuttException(ErrorType.FRIEND_NOT_FOUND)
        return friend
    }
}

data class LegacyNotificationDto(
    val id: String,
    @param:JsonProperty("user_id")
    val userId: String?,
    val title: String,
    val message: String,
    val type: Int,
    val deeplink: String?,
    @param:JsonProperty("created_at")
    val createdAt: String,
)

data class LegacyNotificationCountResponse(
    val count: Long,
)

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
    ): List<LegacyNotificationDto> {
        val notifications = notificationService.getNotifications(user.id!!, offset, limit, explicit > 0)
        val externalIdByUserId = notifications.mapNotNull { it.userId }.associateWith { it.toString() }
        return notifications.map {
            LegacyNotificationDto(
                id = it.id!!.toString(),
                userId = externalIdByUserId[it.userId],
                title = it.title,
                message = it.message,
                type = it.type.value,
                deeplink = it.deeplink,
                createdAt = checkNotNull(it.createdAt).toEpochMilli().toLegacyZonedDateTimeString(),
            )
        }
    }

    @GetMapping("/count")
    fun getUnreadCount(
        @V1CurrentUser user: User,
    ): LegacyNotificationCountResponse = LegacyNotificationCountResponse(count = notificationService.getUnreadCount(user.id!!))
}

data class LegacyBookmarksResponse(
    val year: Int,
    val semester: Int,
    val lectures: List<LegacyBookmarkLectureDto>,
)

data class LegacyExistsResponse(
    val exists: Boolean,
)

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
    ): LegacyBookmarksResponse {
        val display = bookmarkService.getBookmark(user.id!!, year, Semester.fromValue(semester))
        val summaries = evaluationService.findSummariesByLectureIds(display.lectures.mapNotNull { it.id })
        val classTimesMap = lectureService.classTimesByLectureId(display.lectures.mapNotNull { it.id })
        return LegacyBookmarksResponse(
            year = year,
            semester = semester,
            lectures =
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
        @PathVariable lectureId: Long,
    ): LegacyExistsResponse = LegacyExistsResponse(exists = bookmarkService.existsBookmarkLecture(user.id!!, lectureId))

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

data class LegacyVacancyLecturesResponse(
    val lectures: List<LegacyLectureDto>,
)

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
    ): LegacyVacancyLecturesResponse {
        val displays = vacancyNotificationService.getVacancyNotificationLectures(user.id!!)
        val lectureIds = displays.mapNotNull { it.lecture.id }
        val summaries = evaluationService.findSummariesByLectureIds(lectureIds)
        val classTimesMap = lectureService.classTimesByLectureId(lectureIds)
        return LegacyVacancyLecturesResponse(
            lectures =
                displays.map { (lecture, status) ->
                    LegacyLectureDto(
                        lecture,
                        classTimesMap[lecture.id].orEmpty(),
                        clientInfo.language,
                        summaries[lecture.id]?.toLegacyEvSummary(lecture.courseId),
                        status,
                    )
                },
        )
    }

    @GetMapping("/lectures/{lectureId}/state")
    fun exists(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): LegacyExistsResponse = LegacyExistsResponse(exists = vacancyNotificationService.existsVacancyNotification(user.id!!, lectureId))

    @PostMapping("/lectures/{lectureId}")
    fun add(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
    ) {
        vacancyNotificationService.addVacancyNotification(user.id!!, lectureId)
    }

    @DeleteMapping("/lectures/{lectureId}")
    fun delete(
        @V1CurrentUser user: User,
        @PathVariable lectureId: Long,
    ) {
        vacancyNotificationService.deleteVacancyNotification(user.id!!, lectureId)
    }
}

data class LegacyPopupDto(
    val id: String,
    val key: String,
    val imageUri: String,
    @param:JsonProperty("image_url")
    val imageUrl: String,
    val linkUrl: String?,
    val hiddenDays: Int?,
    @param:JsonProperty("hidden_days")
    val hiddenDaysSnake: Int?,
)

@V1Public
data class LegacyListResponse<T>(
    val content: List<T>,
    val totalCount: Int,
)

@RestController
@RequestMapping("/v1/popups")
class V1CompatPopupController(
    private val popupService: PopupService,
    private val storageUriResolver: StorageUriResolver,
) {
    @GetMapping("")
    fun getPopups(
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyListResponse<LegacyPopupDto> =
        LegacyListResponse(
            content =
                popupService.getPopups().map {
                    val imageUri = storageUriResolver.resolve(it.imageOriginUri)
                    LegacyPopupDto(
                        id = it.id!!.toString(),
                        key = it.popupKey,
                        imageUri = imageUri,
                        imageUrl = imageUri,
                        linkUrl = it.linkUrl,
                        hiddenDays = it.hiddenDays,
                        hiddenDaysSnake = it.hiddenDays,
                    )
                },
            totalCount = popupService.getPopups().size,
        )
}

@V1Public
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
    ): PushPreferenceDto = pushPreferenceService.getPushPreferences(user.id!!)

    @PostMapping("")
    fun savePushPreferences(
        @V1CurrentUser user: User,
        @RequestBody dto: PushPreferenceDto,
    ): PushPreferenceDto {
        pushPreferenceService.savePushPreferences(user.id!!, dto)
        return pushPreferenceService.getPushPreferences(user.id!!)
    }
}

@V1Public
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
