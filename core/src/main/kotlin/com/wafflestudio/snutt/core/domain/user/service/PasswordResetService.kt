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

    /**
     * 아이디/비밀번호 찾기는 메일을 실제로 보냈는지 알려주지 않고 항상 200 을 돌려준다. 그래서
     * "메일이 오지 않는다" 는 문의가 들어와도 요청이 어떻게 처리됐는지 확인할 방법이 없어 대응이
     * 어렵다. 두 API 모두 호출량이 하루 수 건 수준이라 상시 기록해도 부담이 없으므로, 모든 요청의
     * 종료 사유를 info 로 남긴다.
     *
     * 이메일은 [emailMaskRegex] 로 마스킹해 앞 3글자만 남기고, 계정을 찾은 경우에는 userId 를 함께
     * 남겨 어느 계정인지 특정할 수 있게 한다.
     *
     * result 는 이렇게 구분한다. 뒤의 셋은 발송을 시도한 뒤의 결과라 [MailSendOutcome] 을 소문자로
     * 바꿔 그대로 쓴다.
     * - no_account: 해당 이메일로 찾은 계정이 없어 발송하지 않음
     * - throttled: 발송 횟수 제한에 걸려 발송하지 않음
     * - failed: 처리 중 예외가 나 발송하지 못함
     * - sent: 수신자에게 발송함
     * - suppressed: 프로바이더가 수신자를 걸러 발송되지 않음
     * - unknown: 프로바이더 응답에 결과가 담겨 오지 않아 발송 여부를 확인할 수 없음
     *
     * 예외가 나도 failed 로 남기고 다시 던진다. 발송 실패야말로 기록이 가장 필요한 경우다.
     */
    private fun logOutcome(
        api: String,
        result: String,
        email: String,
        userIds: List<Long?> = emptyList(),
    ) {
        log.info(
            "{}: result={} email={} userIds={}",
            api,
            result,
            email.replace(emailMaskRegex, "*"),
            userIds,
        )
    }

    private fun resultOf(e: Exception): String =
        if (e is SnuttException && e.error == ErrorType.TOO_MANY_VERIFICATION_CODE_REQUESTS) "throttled" else "failed"

    fun sendLocalIdToEmail(email: String) {
        val trimmed = email.trim()
        var userIds: List<Long?> = emptyList()
        try {
            val accounts = findIdAccounts(trimmed)
            if (accounts.isEmpty()) {
                logOutcome("find-id", "no_account", trimmed)
                return
            }

            userIds = accounts.map { it.user.id }
            store.throttleSend(trimmed)

            val html = renderFindIdMail(accounts)
            val outcome = userMailService.sendFoundAccounts(trimmed, html)
            logOutcome("find-id", outcome.name.lowercase(), trimmed, userIds)
        } catch (e: Exception) {
            logOutcome("find-id", resultOf(e), trimmed, userIds)
            throw e
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
        var userIds: List<Long?> = emptyList()
        try {
            val user = userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(trimmed)
            if (user == null) {
                logOutcome("password-reset", "no_account", trimmed)
                return
            }

            userIds = listOf(user.id)
            val code = VerificationCode.generatePasswordResetCode()
            // store 는 내부에서 throttleSend 를 호출하므로 발송 횟수 제한에 걸리면 여기서 예외가 난다.
            store.store(user.id!!, code)

            val outcome = userMailService.sendPasswordResetCode(trimmed, code)
            logOutcome("password-reset", outcome.name.lowercase(), trimmed, userIds)
        } catch (e: Exception) {
            logOutcome("password-reset", resultOf(e), trimmed, userIds)
            throw e
        }
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
        store.verify(user.id!!, code)
        store.extend(user.id!!, Duration.ofHours(1))
    }

    @Transactional
    fun confirmResetByLocalId(
        localId: String,
        code: String,
        newPassword: String,
    ) {
        val user =
            userRepository.findForUpdateByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
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
