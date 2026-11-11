package com.wafflestudio.snutt.core.domain.maillog.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailSendOutcome
import com.wafflestudio.snutt.core.common.mail.UserMailService
import com.wafflestudio.snutt.core.common.mail.UserMailType
import com.wafflestudio.snutt.core.common.util.EmailMask
import com.wafflestudio.snutt.core.domain.maillog.model.MailLog
import com.wafflestudio.snutt.core.domain.maillog.model.MailLogStatus
import com.wafflestudio.snutt.core.domain.maillog.repository.MailLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@Service
class MailLogService(
    private val mailLogRepository: MailLogRepository,
    private val mailClient: MailClient,
    private val userMailService: UserMailService,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val newTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun record(
        type: UserMailType,
        status: MailLogStatus,
        email: String?,
        userIds: List<Long> = emptyList(),
        requestedLocalId: String? = null,
        errorMessage: String? = null,
    ) {
        logOutcome(type, status, email, userIds, requestedLocalId)
        insert(
            MailLog(
                type = type,
                status = status,
                email = email?.take(MAX_EMAIL_LENGTH),
                requestedLocalId = requestedLocalId?.take(MAX_LOCAL_ID_LENGTH),
                errorMessage = errorMessage,
            ),
        )
    }

    fun send(
        type: UserMailType,
        email: String,
        userIds: List<Long>,
        requestedLocalId: String? = null,
        prepare: () -> Map<String, String>,
    ): MailSendOutcome {
        val mail =
            try {
                userMailService.render(type, prepare())
            } catch (e: Exception) {
                val status = if (e.isThrottled()) MailLogStatus.THROTTLED else MailLogStatus.FAILED
                record(type, status, email, userIds, requestedLocalId, e.toString())
                throw e
            }

        val pending =
            try {
                insert(
                    MailLog(
                        type = type,
                        status = MailLogStatus.PENDING,
                        email = email,
                        requestedLocalId = requestedLocalId,
                        subject = mail.subject,
                        body = mail.html,
                    ),
                )
            } catch (e: Exception) {
                logOutcome(type, MailLogStatus.FAILED, email, userIds, requestedLocalId)
                throw e
            }
        val logId = pending.id!!

        val outcome =
            try {
                mailClient.send(email, mail.subject, mail.html)
            } catch (e: Exception) {
                complete(logId, MailLogStatus.FAILED, e.toString())
                logOutcome(type, MailLogStatus.FAILED, email, userIds, requestedLocalId)
                throw e
            }

        val status =
            when (outcome) {
                MailSendOutcome.SENT -> MailLogStatus.SENT
                MailSendOutcome.SUPPRESSED -> MailLogStatus.SUPPRESSED
            }
        complete(logId, status, errorMessage = null)
        logOutcome(type, status, email, userIds, requestedLocalId)
        return outcome
    }

    fun find(
        email: String?,
        localId: String?,
    ): List<MailLog> =
        when {
            !email.isNullOrBlank() && localId.isNullOrBlank() -> mailLogRepository.findByEmailOrderByIdDesc(email.trim())
            email.isNullOrBlank() && !localId.isNullOrBlank() -> mailLogRepository.findByRequestedLocalIdOrderByIdDesc(localId.trim())
            else -> throw SnuttException(ErrorType.INVALID_PARAMETER)
        }

    fun deleteExpired(): Int = newTransaction.execute { mailLogRepository.deleteCreatedBefore(Instant.now() - RETENTION) }

    private fun insert(mailLog: MailLog): MailLog = newTransaction.execute { mailLogRepository.saveAndFlush(mailLog) }

    private fun complete(
        logId: Long,
        status: MailLogStatus,
        errorMessage: String?,
    ) {
        try {
            newTransaction.execute {
                mailLogRepository.complete(logId, status, errorMessage, Instant.now())
            }
        } catch (e: Exception) {
            log.error("mail log 결과 갱신 실패: id={} status={}", logId, status, e)
        }
    }

    private fun logOutcome(
        type: UserMailType,
        status: MailLogStatus,
        email: String?,
        userIds: List<Long>,
        requestedLocalId: String?,
    ) {
        log.info(
            "{}: result={} email={} requestedLocalId={} userIds={}",
            type.logName,
            status.name.lowercase(),
            email?.let(EmailMask::mask),
            requestedLocalId,
            userIds,
        )
    }

    private fun Exception.isThrottled(): Boolean = this is SnuttException && error == ErrorType.TOO_MANY_VERIFICATION_CODE_REQUESTS

    companion object {
        private val RETENTION: Duration = Duration.ofDays(30)
        private const val MAX_EMAIL_LENGTH = 255
        private const val MAX_LOCAL_ID_LENGTH = 64
    }
}
