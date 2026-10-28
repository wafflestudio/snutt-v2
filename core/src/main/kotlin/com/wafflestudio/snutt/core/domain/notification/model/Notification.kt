package com.wafflestudio.snutt.core.domain.notification.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.wafflestudio.snutt.core.common.model.BaseEntity
import com.wafflestudio.snutt.core.common.push.LocalizedPushMessage
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class NotificationType(
    @JsonValue val value: Int,
) {
    NORMAL(0),
    COURSEBOOK(1),
    LECTURE_UPDATE(2),
    LECTURE_REMOVE(3),
    LECTURE_VACANCY(4),
    FRIEND(5),
    FEATURE_NEW(6),
    DIARY(7),
    ;

    companion object {
        @JsonCreator
        fun fromValue(value: Int): NotificationType =
            entries.find { it.value == value } ?: throw IllegalArgumentException("unknown notification type: $value")
    }
}

@Entity
class Notification(
    var userId: Long? = null,
    var title: String,
    var titleEn: String? = null,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var message: String,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var messageEn: String? = null,
    @Enumerated(EnumType.STRING)
    var type: NotificationType = NotificationType.NORMAL,
    var deeplink: String? = null,
) : BaseEntity()

fun LocalizedPushMessage.toNotification(
    userId: Long?,
    type: NotificationType,
): Notification =
    Notification(
        userId = userId,
        title = title.ko,
        titleEn = title.en,
        message = body.ko,
        messageEn = body.en,
        type = type,
        deeplink = urlScheme,
    )
