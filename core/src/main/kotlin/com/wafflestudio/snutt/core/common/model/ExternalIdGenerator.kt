package com.wafflestudio.snutt.core.common.model

import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

// Mongo ObjectId 레이아웃(4B epoch + 5B process random + 3B counter)의 24-hex 문자열.
// bson 명세: https://www.mongodb.com/docs/manual/reference/bson-types/#objectid
object ExternalIdGenerator {
    private val processRandom = ByteArray(5).also { SecureRandom().nextBytes(it) }
    private val counter = AtomicInteger(SecureRandom().nextInt())

    fun generate(): String {
        val bytes = ByteArray(12)
        val epoch = Instant.now().epochSecond.toInt()
        bytes[0] = (epoch shr 24).toByte()
        bytes[1] = (epoch shr 16).toByte()
        bytes[2] = (epoch shr 8).toByte()
        bytes[3] = epoch.toByte()
        processRandom.copyInto(bytes, 4)
        val count = counter.incrementAndGet()
        bytes[9] = (count shr 16).toByte()
        bytes[10] = (count shr 8).toByte()
        bytes[11] = count.toByte()
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
