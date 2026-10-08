package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.MailSendOutcome
import com.wafflestudio.snutt.core.common.mail.UserMailService
import com.wafflestudio.snutt.core.common.util.CodeChallengeStore
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.common.util.VerificationCode
import com.wafflestudio.snutt.core.domain.auth.AuthProvider
import com.wafflestudio.snutt.core.domain.auth.authProvidersOf
import com.wafflestudio.snutt.core.domain.auth.service.AuthService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserSocialAuthRepository
import org.slf4j.LoggerFactory
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
    private val userMailService: UserMailService,
    private val passwordEncoder: PasswordEncoder,
    private val authService: AuthService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val store = CodeChallengeStore(redisTemplate, jsonMapper, "reset-password", ttl = Duration.ofMinutes(15))

    private data class FoundAccount(
        val user: User,
        val providers: List<AuthProvider>,
    )

    companion object {
        private val emailMaskRegex = Regex("(?<=.{3}).(?=.*@)")
    }

    private fun logOutcome(
        api: String,
        result: String,
        email: String,
        userIds: List<Long> = emptyList(),
    ) {
        log.info(
            "{}: result={} email={} userIds={}",
            api,
            result,
            email.replace(emailMaskRegex, "*"),
            userIds,
        )
    }

    private fun resultOf(e: Throwable): String =
        if (e is SnuttException && e.error == ErrorType.TOO_MANY_VERIFICATION_CODE_REQUESTS) "throttled" else "failed"

    private fun logged(
        api: String,
        email: String,
        userIds: List<Long>,
        send: () -> MailSendOutcome,
    ) {
        val result = runCatching(send)
        logOutcome(api, result.fold({ it.name.lowercase() }, ::resultOf), email, userIds)
        result.getOrThrow()
    }

    fun sendLocalIdToEmail(email: String) {
        val trimmed = email.trim()
        val accounts = findIdAccounts(trimmed)
        if (accounts.isEmpty()) {
            logOutcome("find-id", "no_account", trimmed)
            return
        }
        logged("find-id", trimmed, accounts.map { it.user.id!! }) {
            store.throttleSend(trimmed)
            userMailService.sendFoundAccounts(trimmed, renderFindIdMail(accounts))
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
            logOutcome("password-reset", "no_account", trimmed)
            return
        }
        logged("password-reset", trimmed, listOf(user.id!!)) {
            val code = VerificationCode.generatePasswordResetCode()
            store.store(user.id!!, code)
            userMailService.sendPasswordResetCode(trimmed, code)
        }
    }

    fun verifyResetCode(
        userId: Long,
        code: String,
    ) {
        store.verify(userId, code)
        store.extend(userId, Duration.ofHours(1))
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
    fun confirmResetByEmail(
        email: String,
        code: String,
        newPassword: String,
    ) {
        val user =
            userRepository.findForUpdateByEmailAndIsEmailVerifiedTrueAndActiveTrue(email.trim())
                ?: throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        confirmReset(user, code, newPassword)
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
