package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailType
import com.wafflestudio.snutt.core.common.util.CodeChallengeStore
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.common.util.VerificationCode
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.repository.UserSessionRepository
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserSocialAuthRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
class PasswordResetService(
    redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val userSocialAuthRepository: UserSocialAuthRepository,
    private val userSessionRepository: UserSessionRepository,
    private val mailClient: MailClient,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val store = CodeChallengeStore(redisTemplate, "reset-password", ttl = Duration.ofMinutes(15))

    companion object {
        private val emailMaskRegex = Regex("(?<=.{3}).(?=.*@)")
    }

    @Transactional
    fun sendLocalIdToEmail(email: String) {
        val accountInfo = findIdAccountInfo(email) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        store.throttleSend(email.trim())
        mailClient.sendCodeMail(MailType.VERIFICATION, email.trim(), accountInfo)
    }

    /** 아이디 찾기 요청도 이메일 존재 여부를 응답으로 노출하지 않는다(v2). */
    @Transactional
    fun sendLocalIdToEmailQuietly(email: String) {
        val accountInfo = findIdAccountInfo(email) ?: return
        store.throttleSend(email.trim())
        mailClient.sendCodeMail(MailType.VERIFICATION, email.trim(), accountInfo)
    }

    private fun findIdAccountInfo(email: String): String? {
        val users = userRepository.findAllByEmailAndActiveTrue(email.trim())
        return buildFindIdAccountInfo(users).ifEmpty { null }
    }

    private fun buildFindIdAccountInfo(users: List<User>): String {
        val socialProvidersByUser =
            userSocialAuthRepository
                .findByUserIdIn(users.mapNotNull { it.id })
                .groupBy({ it.userId }, { it.provider })

        fun providersOf(user: User): List<AuthProvider> =
            buildList {
                if (user.localId != null) add(AuthProvider.LOCAL)
                socialProvidersByUser[user.id].orEmpty().forEach { add(it) }
            }
        val accounts =
            users
                .filter { it.localId != null || !socialProvidersByUser[it.id].isNullOrEmpty() }
                .sortedBy { it.createdAt }
        return when (accounts.size) {
            0 -> ""
            1 ->
                renderFindIdAccount(accounts[0], providersOf(accounts[0]))
            else ->
                accounts
                    .mapIndexed { index, user -> "<b>&lt;계정 ${index + 1}&gt;</b><br/>" + renderFindIdAccount(user, providersOf(user)) }
                    .joinToString(separator = "<br/>")
        }
    }

    private fun renderFindIdAccount(
        user: User,
        providers: List<AuthProvider>,
    ): String =
        buildList {
            user.localId?.let { add("<b>[아이디]</b> $it") }
            if (providers.isNotEmpty()) add("<b>[소셜 로그인 수단]</b> ${providers.joinToString(", ")}")
        }.joinToString(separator = "<br/>", postfix = "<br/>")

    @Transactional
    fun requestReset(email: String) {
        val user = findResetTargetUser(email) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        sendResetCode(user, email)
    }

    /** 이메일 존재 여채를 응답으로 노출하지 않는다(v2). 없으면 아무 일도 하지 않는다. */
    @Transactional
    fun requestResetQuietly(email: String) {
        val user = findResetTargetUser(email) ?: return
        sendResetCode(user, email)
    }

    private fun findResetTargetUser(email: String): User? = userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email.trim())

    private fun sendResetCode(
        user: User,
        email: String,
    ) {
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

    /** 존재하지 않는 이메일과 코드 불일치를 구분하지 않는다(v2). */
    @Transactional
    fun confirmResetQuietly(
        email: String,
        code: String,
        newPassword: String,
    ) {
        val user =
            userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email.trim())
                ?: throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        confirmResetFor(user, code, newPassword)
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
        confirmResetFor(user, code, newPassword)
    }

    private fun confirmResetFor(
        user: User,
        code: String,
        newPassword: String,
    ) {
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
