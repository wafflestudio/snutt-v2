package com.wafflestudio.snutt.core.common.mail

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import org.slf4j.LoggerFactory

object MailOutcomeLog {
    private val log = LoggerFactory.getLogger(MailOutcomeLog::class.java)
    private val emailMaskRegex = Regex("(?<=.{3}).(?=.*@)")

    fun outcome(
        api: String,
        result: String,
        email: String?,
        userIds: List<Long> = emptyList(),
        requestedLocalId: String? = null,
    ) {
        log.info(
            "{}: result={} email={} requestedLocalId={} userIds={}",
            api,
            result,
            email?.replace(emailMaskRegex, "*"),
            requestedLocalId,
            userIds,
        )
    }

    fun logged(
        api: String,
        email: String,
        userIds: List<Long>,
        requestedLocalId: String? = null,
        send: () -> MailSendOutcome,
    ) {
        val result = runCatching(send)
        outcome(api, result.fold({ it.name.lowercase() }, ::resultOf), email, userIds, requestedLocalId)
        result.getOrThrow()
    }

    private fun resultOf(e: Throwable): String =
        if (e is SnuttException && e.error == ErrorType.TOO_MANY_VERIFICATION_CODE_REQUESTS) "throttled" else "failed"
}
