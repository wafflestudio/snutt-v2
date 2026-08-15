package com.wafflestudio.snutt.core.domain.user.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailType
import com.wafflestudio.snutt.core.common.util.PasswordPolicy
import com.wafflestudio.snutt.core.domain.auth.repository.UserSessionRepository
import com.wafflestudio.snutt.core.domain.user.event.UserCredentialChangedEvent
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import kotlin.random.Random

// 비밀번호 초기화: 검증된 이메일로 6자리 코드를 보내고, 코드 확인 후 비밀번호를 교체한다.
// 코드는 (사용자 id 키, 3분 TTL)로 Redis에 저장한다. 이메일 인증(EmailVerificationService)과 동일한 흐름.
@Service
class PasswordResetService(
    private val redisTemplate: StringRedisTemplate,
    private val userRepository: UserRepository,
    private val userSessionRepository: UserSessionRepository,
    private val mailClient: MailClient,
    private val passwordEncoder: PasswordEncoder,
    private val eventPublisher: ApplicationEventPublisher,
) {
    companion object {
        private const val RESET_CODE_PREFIX = "reset-password-code:"
        private val codeTtl: Duration = Duration.ofMinutes(3)
        private val emailMaskRegex = Regex("(?<=.{3}).(?=.*@)")
    }

    // 아이디 찾기: 가입된 이메일로 아이디/소셜 수단 정보를 발송한다
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

    // 초기화 코드 발송. 검증된 이메일만 수신자로 삼는다
    @Transactional
    fun requestReset(email: String) {
        val user =
            userRepository.findByEmailAndIsEmailVerifiedTrueAndActiveTrue(email.trim())
                ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val userId = requireNotNull(user.id) { "persisted user must have an id" }
        val key = RESET_CODE_PREFIX + userId
        if (redisTemplate.hasKey(key)) throw SnuttException(ErrorType.TOO_MANY_VERIFICATION_CODE_REQUEST)
        val code = Random.nextInt(100000, 1000000).toString()
        redisTemplate.opsForValue().set(key, code, codeTtl)
        mailClient.sendCodeMail(MailType.PASSWORD_RESET, email.trim(), code)
    }

    // 구 클라이언트 호환: localId로 사용자를 찾아 마스킹된 이메일을 준다
    @Transactional(readOnly = true)
    fun getMaskedEmailByLocalId(localId: String): String {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val email = user.email ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        return email.replace(emailMaskRegex, "*")
    }

    // 구 클라이언트 호환: 코드만 검증한다 (교체는 이후 요청에서)
    @Transactional(readOnly = true)
    fun verifyResetCodeByLocalId(
        localId: String,
        code: String,
    ) {
        val user = userRepository.findByLocalIdAndActiveTrue(localId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val userId = requireNotNull(user.id)
        val stored =
            redisTemplate.opsForValue().get(RESET_CODE_PREFIX + userId)
                ?: throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        if (stored != code) throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
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

    // 코드 확인 후 비밀번호 교체. 보안상 기존 세션을 모두 폐기한다
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
        val key = RESET_CODE_PREFIX + userId
        val stored = redisTemplate.opsForValue().get(key) ?: throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        if (stored != code) throw SnuttException(ErrorType.INVALID_VERIFICATION_CODE)
        if (!PasswordPolicy.isValidPassword(newPassword)) throw SnuttException(ErrorType.INVALID_PASSWORD)
        user.localPw = passwordEncoder.encode(newPassword)
        userRepository.save(user)
        redisTemplate.delete(key)
        userSessionRepository.revokeAllByUserId(userId)
        eventPublisher.publishEvent(UserCredentialChangedEvent(userId))
    }
}
