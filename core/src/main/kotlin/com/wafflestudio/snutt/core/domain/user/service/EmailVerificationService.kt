package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailType
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.random.Random

// v1 이메일 인증 흐름 이식: SNU 메일 6자리 코드, 3분 TTL + 재요청 제한 (Redis)
@Service
class EmailVerificationService(
    private val redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val mailClient: MailClient,
) {
    companion object {
        private const val CODE_PREFIX = "verification:code:"
        private val snuMailRegex = Regex("^[a-zA-Z0-9._%+-]+@snu\\.ac\\.kr$")
        private val codeTtl: Duration = Duration.ofMinutes(3)
        private val jsonMapper = JsonMapper.builder().findAndAddModules().build()
    }

    @Transactional
    fun sendVerificationCode(
        user: User,
        email: String,
    ) {
        val trimmed = email.trim()
        if (user.isEmailVerified) throw SnuttException(ErrorType.EMAIL_ALREADY_VERIFIED)
        if (!snuMailRegex.matches(trimmed)) throw SnuttException(ErrorType.INVALID_EMAIL)
        if (userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(trimmed) != null) {
            throw SnuttException(ErrorType.DUPLICATE_EMAIL)
        }
        val key = CODE_PREFIX + user.id
        if (redisTemplate.hasKey(key)) throw SnuttException(ErrorType.TOO_MANY_VERIFICATION_CODE_REQUEST)
        val code = Random.nextInt(100000, 1000000).toString()
        redisTemplate.opsForValue().set(key, jsonMapper.writeValueAsString(StoredCode(trimmed, code)), codeTtl)
        mailClient.sendCodeMail(MailType.VERIFICATION, trimmed, code)
    }

    @Transactional
    fun verifyEmail(
        user: User,
        code: String,
    ) {
        val stored =
            redisTemplate
                .opsForValue()
                .get(CODE_PREFIX + user.id)
                ?.let { runCatching { jsonMapper.readValue(it, StoredCode::class.java) }.getOrNull() }
                ?: throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        if (stored.code != code) throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        user.email = stored.email
        user.isEmailVerified = true
        userRepository.save(user)
        redisTemplate.delete(CODE_PREFIX + user.id)
    }

    @Transactional
    fun resetEmailVerification(user: User) {
        user.isEmailVerified = false
        userRepository.save(user)
    }

    private data class StoredCode(
        val email: String,
        val code: String,
    )
}
