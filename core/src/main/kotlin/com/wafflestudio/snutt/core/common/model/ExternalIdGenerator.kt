package com.wafflestudio.snutt.core.common.model

import java.security.SecureRandom

object ExternalIdGenerator {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return buildString {
            for (byte in bytes) {
                append(ALPHABET[(byte.toInt() shr 4) and 0xF])
                append(ALPHABET[byte.toInt() and 0xF])
            }
        }
    }
}
