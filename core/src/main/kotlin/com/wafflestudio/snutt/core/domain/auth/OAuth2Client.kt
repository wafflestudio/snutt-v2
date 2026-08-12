package com.wafflestudio.snutt.core.domain.auth

interface OAuth2Client {
    fun getMe(token: String): OAuth2UserResponse?
}

data class OAuth2UserResponse(
    val socialId: String,
    val name: String?,
    val email: String?,
    val isEmailVerified: Boolean,
    val transferInfo: String? = null,
)
