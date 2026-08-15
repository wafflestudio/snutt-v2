package com.wafflestudio.snutt.core.common.util

import java.security.SecureRandom

object VerificationCode {
    private val secureRandom = SecureRandom()

    const val MAX_ATTEMPTS = 5

    fun generate(): String = "%06d".format(secureRandom.nextInt(1_000_000))
}
