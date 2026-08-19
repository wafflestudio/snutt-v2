package com.wafflestudio.snutt.core.domain.friend.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "friend")
class Friend(
    var fromUserId: Long,
    var toUserId: Long,
    var fromDisplayName: String? = null,
    var toDisplayName: String? = null,
    var isAccepted: Boolean = false,
) : BaseEntity() {
    fun includes(userId: Long): Boolean = fromUserId == userId || toUserId == userId

    fun getPartnerUserId(userId: Long): Long {
        check(includes(userId))
        return if (fromUserId == userId) toUserId else fromUserId
    }

    fun getPartnerDisplayName(userId: Long): String? {
        check(includes(userId))
        return if (fromUserId == userId) toDisplayName else fromDisplayName
    }

    fun updatePartnerDisplayName(
        userId: Long,
        displayName: String,
    ) {
        check(includes(userId))
        if (fromUserId == userId) toDisplayName = displayName else fromDisplayName = displayName
    }
}
