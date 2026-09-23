package com.wafflestudio.snutt.core.common.util

import java.security.SecureRandom
import java.util.Base64

object VerificationCode {
    private val secureRandom = SecureRandom()

    const val MAX_ATTEMPTS = 5

    fun generateEmailVerificationCode(): String = "%06d".format(secureRandom.nextInt(1_000_000))

    fun generatePasswordResetCode(): String {
        val bytes = ByteArray(6)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
