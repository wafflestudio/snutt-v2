package com.wafflestudio.snutt.core.domain.user.event

data class UserRegisteredEvent(
    val userId: Long,
)

data class UserCredentialChangedEvent(
    val userId: Long,
)
