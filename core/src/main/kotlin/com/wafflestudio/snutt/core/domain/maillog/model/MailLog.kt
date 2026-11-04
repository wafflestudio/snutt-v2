package com.wafflestudio.snutt.core.domain.maillog.model

import com.wafflestudio.snutt.core.common.mail.UserMailType
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class MailLogStatus {
    PENDING,
    SENT,
    SUPPRESSED,
    FAILED,
    THROTTLED,
    NO_ACCOUNT,
    NO_EMAIL,
}

@Entity
class MailLog(
    @Enumerated(EnumType.STRING)
    val type: UserMailType,
    @Enumerated(EnumType.STRING)
    val status: MailLogStatus,
    val email: String?,
    val requestedLocalId: String? = null,
    val subject: String? = null,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    val body: String? = null,
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    val errorMessage: String? = null,
) : BaseEntity()
