package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.error.conflictAs
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailType
import com.wafflestudio.snutt.core.common.util.CodeChallengeStore
import com.wafflestudio.snutt.core.common.util.VerificationCode
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.repository.findByIdOrNull
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
        userId: Long,
        email: String,
    ) {
        val user = getUser(userId)
        val trimmed = email.trim()
        if (user.isEmailVerified) throw SnuttException(ErrorType.EMAIL_ALREADY_VERIFIED)
        if (!snuMailRegex.matches(trimmed)) throw SnuttException(ErrorType.INVALID_EMAIL)
        if (userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(trimmed) != null) {
            throw SnuttException(ErrorType.DUPLICATE_EMAIL)
        }
        val code = VerificationCode.generate()
        store.store(userId, code, payload = trimmed)
        mailClient.sendCodeMail(MailType.VERIFICATION, trimmed, code)
    }

    @Transactional
    fun verifyEmail(
        userId: Long,
        code: String,
    ) {
        val user = getUser(userId)
        val email = store.verify(userId, code)
        user.email = email
        user.isEmailVerified = true
        conflictAs(ErrorType.DUPLICATE_EMAIL) { userRepository.save(user) }
        store.clear(userId)
    }

    @Transactional
    fun resetEmailVerification(userId: Long) {
        val user = getUser(userId)
        user.isEmailVerified = false
        userRepository.save(user)
    }

    private fun getUser(userId: Long): User = userRepository.findByIdOrNull(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
}
