package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailType
import com.wafflestudio.snutt.core.common.util.CodeChallengeStore
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.common.util.VerificationCode
import com.wafflestudio.snutt.core.domain.auth.repository.UserSessionRepository
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PasswordResetService(
    redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val userSessionRepository: UserSessionRepository,
    private val mailClient: MailClient,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val store = CodeChallengeStore(redisTemplate, "reset-password")

    companion object {
        private val emailMaskRegex = Regex("(?<=.{3}).(?=.*@)")
    }

    @Transactional
    fun sendLocalIdToEmail(email: String) {
        val users = userRepository.findAllByEmailAndActiveTrue(email.trim())
        val accountInfo = buildFindIdAccountInfo(users)
        if (accountInfo.isBlank()) throw SnuttException(ErrorType.USER_NOT_FOUND)
        mailClient.sendCodeMail(MailType.VERIFICATION, email.trim(), accountInfo)
    }

    private fun buildFindIdAccountInfo(users: List<User>): String {
        val accounts = users.filter { it.localId != null || it.authProviders.isNotEmpty() }.sortedBy { it.createdAt }
        return when (accounts.size) {
            0 -> ""
            1 -> renderFindIdAccount(accounts[0])
            else ->
                accounts
                    .mapIndexed { index, user -> "<b>&lt;계정 ${index + 1}&gt;</b><br/>" + renderFindIdAccount(user) }
                    .joinToString(separator = "<br/>")
        }
    }

    private fun renderFindIdAccount(user: User): String =
        buildList {
            user.localId?.let { add("<b>[아이디]</b> $it") }
            if (user.authProviders.isNotEmpty()) add("<b>[소셜 로그인 수단]</b> ${user.authProviders.joinToString(", ")}")
        }.joinToString(separator = "<br/>", postfix = "<br/>")

    @Transactional
    fun requestReset(email: String) {
        val user =
            userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email.trim())
                ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val userId = requireNotNull(user.id) { "persisted user must have an id" }
        val code = VerificationCode.generate()
        store.store(userId, code)
        mailClient.sendCodeMail(MailType.PASSWORD_RESET, email.trim(), code)
    }

    @Transactional(readOnly = true)
    fun getMaskedEmailByLocalId(localId: String): String {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val email = user.email ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        return email.replace(emailMaskRegex, "*")
    }

    @Transactional(readOnly = true)
    fun verifyResetCodeByLocalId(
        localId: String,
        code: String,
    ) {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        store.verify(requireNotNull(user.id), code)
    }

    @Transactional
    fun confirmResetByLocalId(
        localId: String,
        code: String,
        newPassword: String,
    ) {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        confirmReset(user.email ?: throw SnuttException(ErrorType.USER_NOT_FOUND), code, newPassword)
    }

    @Transactional
    fun confirmReset(
        email: String,
        code: String,
        newPassword: String,
    ) {
        val user =
            userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email.trim())
                ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val userId = requireNotNull(user.id) { "persisted user must have an id" }
        store.verify(userId, code)
        if (!PasswordPolicy.isValidPassword(newPassword)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        user.localPw = passwordEncoder.encode(newPassword)
        userRepository.save(user)
        store.clear(userId)
        userSessionRepository.revokeAllByUserId(userId)
        eventPublisher.publishEvent(UserCredentialChangedEvent(userId))
    }
}
