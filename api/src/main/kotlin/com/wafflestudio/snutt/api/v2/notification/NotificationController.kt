package com.wafflestudio.snutt.api.v2.notification

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.pagination.CursorPage
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.service.NotificationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class NotificationResponse(
    val id: Long,
    val userId: Long?,
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
) {
    @GetMapping("")
    fun getNotifications(
        @CurrentUserId userId: Long,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") explicit: Int,
    ): CursorPage<NotificationResponse> {
        val page = notificationService.getNotifications(userId, cursor, limit, explicit > 0)
        return CursorPage(
            content = page.content.map { it.toResponse() },
            cursor = page.cursor,
            size = page.size,
            last = page.last,
            totalCount = page.totalCount,
        )
    }

    @GetMapping("/count")
    fun getUnreadCount(
        @CurrentUserId userId: Long,
    ): NotificationCountResponse = NotificationCountResponse(notificationService.getUnreadCount(userId))
}

private fun Notification.toResponse(): NotificationResponse =
    NotificationResponse(
        id = id!!,
        userId = userId,
        title = title,
        message = message,
        type = type,
        deeplink = deeplink,
        createdAt = checkNotNull(createdAt).toEpochMilli(),
    )
