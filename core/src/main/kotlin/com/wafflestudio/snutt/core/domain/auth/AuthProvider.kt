package com.wafflestudio.snutt.core.domain.auth

import com.wafflestudio.snutt.core.domain.user.model.User

enum class AuthProvider(
    val value: String,
    val korName: String,
) {
    LOCAL("local", "아이디/비밀번호"),
    FACEBOOK("facebook", "페이스북"),
    APPLE("apple", "애플"),
    GOOGLE("google", "구글"),
    KAKAO("kakao", "카카오"),
    ;

    companion object {
        private val mapping = entries.associateBy { e -> e.value }

        fun from(value: String): AuthProvider? = mapping[value]
    }
}

fun authProvidersOf(
    user: User,
    socialProviders: Collection<AuthProvider>,
): List<AuthProvider> =
    buildList {
        if (user.localId != null) add(AuthProvider.LOCAL)
        addAll(socialProviders.distinct().sortedBy { it.ordinal })
    }
