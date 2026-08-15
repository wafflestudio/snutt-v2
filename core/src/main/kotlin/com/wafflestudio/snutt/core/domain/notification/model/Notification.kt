package com.wafflestudio.snutt.core.domain.notification.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
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
@Table(name = "notification")
class Notification(
    var userId: Long? = null,
    var title: String,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    var message: String,
    @Enumerated(EnumType.STRING)
    var type: NotificationType = NotificationType.NORMAL,
    var deeplink: String? = null,
) : ExternalIdEntity()
