package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.UserMailType
import com.wafflestudio.snutt.core.common.util.CodeChallengeStore
import com.wafflestudio.snutt.core.common.util.EmailMask
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.common.util.VerificationCode
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.authProvidersOf
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.auth.service.PasswordResetTokenPayload
import com.wafflestudio.snutt.core.domain.auth.service.PasswordResetTokenService
import com.wafflestudio.snutt.core.domain.maillog.model.MailLogStatus
import com.wafflestudio.snutt.core.domain.maillog.service.MailLogService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserSocialAuthRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@Service
class PasswordResetService(
    redisTemplate: StringRedisTemplate,
    jsonMapper: JsonMapper,
    private val userRepository: UserRepository,
    private val userSocialAuthRepository: UserSocialAuthRepository,
    private val mailLogService: MailLogService,
    private val passwordEncoder: PasswordEncoder,
    private val authService: AuthService,
    private val passwordResetTokenService: PasswordResetTokenService,
) {
    private val store = CodeChallengeStore(redisTemplate, jsonMapper, "reset-password", ttl = Duration.ofMinutes(15))

    private data class FoundAccount(
        val user: User,
        val providers: List<AuthProvider>,
    )

    fun sendLocalIdToEmail(email: String) {
        val trimmed = email.trim()
        val accounts = findIdAccounts(trimmed)
        if (accounts.isEmpty()) {
            mailLogService.record(UserMailType.FIND_ID, MailLogStatus.NO_ACCOUNT, trimmed)
            return
        }
        mailLogService.send(UserMailType.FIND_ID, trimmed, accounts.map { it.user.id!! }) {
            store.throttleSend(trimmed)
            mapOf("email" to trimmed, "accountInfo" to renderFindIdMail(accounts))
        }
    }

    private fun findIdAccounts(email: String): List<FoundAccount> {
        val users = userRepository.findAllByEmailAndActiveTrue(email)
        val socialProvidersByUser =
            userSocialAuthRepository
                .findByUserIdIn(users.map { it.id!! })
                .groupBy({ it.userId }, { it.provider })
        return users
            .sortedBy { it.createdAt }
            .map { FoundAccount(it, authProvidersOf(it, socialProvidersByUser[it.id].orEmpty())) }
            .filter { it.providers.isNotEmpty() }
    }

    private fun renderFindIdMail(accounts: List<FoundAccount>): String {
        if (accounts.size == 1) return renderFindIdAccount(accounts.single())
        return accounts
            .mapIndexed { index, account -> "<b>&lt;계정 ${index + 1}&gt;</b><br/>" + renderFindIdAccount(account) }
            .joinToString(separator = "<br/>")
    }

    private fun renderFindIdAccount(account: FoundAccount): String {
        val social = account.providers.filter { it != AuthProvider.LOCAL }.map { it.korName }
        return buildList {
            account.user.localId?.let { add("<b>[아이디]</b> $it") }
            if (social.isNotEmpty()) add("<b>[소셜 로그인 수단]</b> ${social.joinToString(", ")}")
        }.joinToString(separator = "<br/>", postfix = "<br/>")
    }

    fun requestReset(email: String) {
        val trimmed = email.trim()
        val user = userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(trimmed)
        if (user == null) {
            mailLogService.record(UserMailType.PASSWORD_RESET, MailLogStatus.NO_ACCOUNT, trimmed)
            return
        }
        sendResetCode(user, trimmed)
    }

    fun requestResetByLocalId(localId: String): String {
        val trimmed = localId.trim()
        val user = userRepository.findByLocalIdAndActiveTrue(trimmed)
        if (user == null) {
            mailLogService.record(UserMailType.PASSWORD_RESET, MailLogStatus.NO_ACCOUNT, email = null, requestedLocalId = trimmed)
            throw SnuttException(ErrorType.USER_NOT_FOUND)
        }
        val email = user.email
        if (email.isNullOrBlank()) {
            mailLogService.record(
                UserMailType.PASSWORD_RESET,
                MailLogStatus.NO_EMAIL,
                email = null,
                userIds = listOf(user.id!!),
                requestedLocalId = trimmed,
            )
            throw SnuttException(ErrorType.EMAIL_NOT_REGISTERED)
        }
        sendResetCode(user, email, trimmed)
        return EmailMask.mask(email)
    }

    private fun sendResetCode(
        user: User,
        email: String,
        requestedLocalId: String? = null,
    ) {
        mailLogService.send(UserMailType.PASSWORD_RESET, email, listOf(user.id!!), requestedLocalId) {
            val code = VerificationCode.generatePasswordResetCode()
            store.store(user.id!!, code)
            mapOf("code" to code)
        }
    }

    fun verifyResetCode(
        userId: Long,
        code: String,
    ) {
        store.verify(userId, code)
        store.extend(userId, Duration.ofHours(1))
    }

    fun verifyResetCodeByLocalId(
        localId: String,
        code: String,
    ): String {
        val user =
            userRepository.findByLocalIdAndActiveTrue(localId.trim())
                ?: throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        val userId = user.id!!
        store.verify(userId, code)
        store.clear(userId)
        return passwordResetTokenService.issue(
            PasswordResetTokenPayload(userId = userId, tokenVersion = user.tokenVersion),
        )
    }

    @Transactional
    fun confirmReset(
        userId: Long,
        code: String,
        newPassword: String,
    ) {
        val user = userRepository.findForUpdateByIdAndActiveTrue(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        confirmReset(user, code, newPassword)
    }

    @Transactional
    fun confirmResetWithToken(
        resetToken: String,
        newPassword: String,
    ) {
        val payload = passwordResetTokenService.verify(resetToken)
        val user =
            userRepository.findForUpdateByIdAndActiveTrue(payload.userId)
                ?: throw SnuttException(ErrorType.INVALID_PASSWORD_RESET_TOKEN)
        if (user.tokenVersion != payload.tokenVersion) throw SnuttException(ErrorType.INVALID_PASSWORD_RESET_TOKEN)
        if (!PasswordPolicy.isValidPassword(newPassword)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        user.localPw = passwordEncoder.encode(newPassword)
        authService.revokeSessions(user)
    }

    private fun confirmReset(
        user: User,
        code: String,
        newPassword: String,
    ) {
        val userId = user.id!!
        store.verify(userId, code)
        if (!PasswordPolicy.isValidPassword(newPassword)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        user.localPw = passwordEncoder.encode(newPassword)
        store.clear(userId)
        authService.revokeSessions(user)
    }
}
