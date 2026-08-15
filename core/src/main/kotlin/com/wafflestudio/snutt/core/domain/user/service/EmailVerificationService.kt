package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailType
import com.wafflestudio.snutt.core.common.util.CodeChallengeStore
import com.wafflestudio.snutt.core.common.util.VerificationCode
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EmailVerificationService(
    redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val mailClient: MailClient,
) {
    private val store = CodeChallengeStore(redisTemplate, "verification")

    companion object {
        private val snuMailRegex = Regex("^[a-zA-Z0-9._%+-]+@snu\\.ac\\.kr$")
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
        val code = VerificationCode.generate()
        store.store(user.id!!, code, payload = trimmed)
        mailClient.sendCodeMail(MailType.VERIFICATION, trimmed, code)
    }

    @Transactional
    fun verifyEmail(
        user: User,
        code: String,
    ) {
        val email = store.verify(user.id!!, code)
        user.email = email
        user.isEmailVerified = true
        userRepository.save(user)
        store.clear(user.id!!)
    }

    @Transactional
    fun resetEmailVerification(user: User) {
        user.isEmailVerified = false
        userRepository.save(user)
    }
}
