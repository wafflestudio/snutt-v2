package com.wafflestudio.snutt.api.v2.notification

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.NotificationService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class NotificationResponse(
    val id: String,
    val userId: String?,
    val title: String,
    val message: String,
    val type: NotificationType,
    val deeplink: String?,
    val createdAt: Long,
)

data class NotificationCountResponse(
    val count: Long,
)

@RestController
@RequestMapping("/v2/notifications")
class NotificationController(
    private val notificationService: NotificationService,
    private val userService: UserService,
) {
    @GetMapping("")
    fun getNotifications(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "0") offset: Long,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") explicit: Int,
    ): List<NotificationResponse> {
        val notifications = notificationService.getNotifications(user, offset, limit, explicit > 0)
        val externalIdByUserId =
            userService.getExternalIds(notifications.mapNotNull { it.userId })
        return notifications.map { it.toResponse(externalIdByUserId[it.userId]) }
    }

    @GetMapping("/count")
    fun getUnreadCount(
        @CurrentUser user: User,
    ): NotificationCountResponse = NotificationCountResponse(notificationService.getUnreadCount(user))
}

private fun Notification.toResponse(userExternalId: String?): NotificationResponse =
    NotificationResponse(
        id = externalId,
        userId = userExternalId,
        title = title,
        message = message,
        type = type,
        deeplink = deeplink,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
    )
