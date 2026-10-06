package com.wafflestudio.snutt.core.common.mail

import com.oracle.bmc.Region
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider
import com.oracle.bmc.emaildataplane.EmailDPClient
import com.oracle.bmc.emaildataplane.model.EmailAddress
import com.oracle.bmc.emaildataplane.model.Recipients
import com.oracle.bmc.emaildataplane.model.Sender
import com.oracle.bmc.emaildataplane.model.SubmitEmailDetails
import com.oracle.bmc.emaildataplane.requests.SubmitEmailRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/**
 * 메일 제출 결과.
 *
 * OCI Email Delivery 는 거른 수신자가 있어도 요청 자체는 성공으로 응답하므로, 호출이 예외 없이 끝난
 * 것만으로는 발송 여부를 알 수 없다. 응답에 담긴 제외된 수신자 목록을 보고 판단한다.
 */
enum class MailSendOutcome {
    /** 수신자에게 발송됐다. */
    SENT,

    /** 프로바이더가 수신자를 걸러 발송되지 않았다. */
    SUPPRESSED,

    /** 응답에 결과가 담겨 오지 않아 발송 여부를 확인할 수 없다. */
    UNKNOWN,
}

interface MailClient {
    fun send(
        to: String,
        subject: String,
        html: String,
    ): MailSendOutcome
}

@Service
@Profile("!test")
class OciMailClient(
    authProvider: BasicAuthenticationDetailsProvider,
    @param:Value("\${snutt.mail.compartment-id}") private val compartmentId: String,
    @param:Value("\${snutt.mail.sender-address:snutt@wafflestudio.com}") private val senderAddress: String,
    @param:Value("\${snutt.mail.sender-name:SNUTT}") private val senderName: String,
    @Value("\${snutt.mail.region:ap-chuncheon-1}") region: String,
) : MailClient {
    private val client = EmailDPClient.builder().region(Region.fromRegionId(region)).build(authProvider)

    override fun send(
        to: String,
        subject: String,
        html: String,
    ): MailSendOutcome {
        val details =
            SubmitEmailDetails
                .builder()
                .sender(
                    Sender
                        .builder()
                        .senderAddress(
                            EmailAddress
                                .builder()
                                .email(senderAddress)
                                .name(senderName)
                                .build(),
                        ).compartmentId(compartmentId)
                        .build(),
                ).recipients(
                    Recipients
                        .builder()
                        .to(listOf(EmailAddress.builder().email(to).build()))
                        .build(),
                ).subject(subject)
                .bodyHtml(html)
                .build()
        val submitted =
            client
                .submitEmail(SubmitEmailRequest.builder().submitEmailDetails(details).build())
                .emailSubmittedResponse
        return when {
            submitted == null -> MailSendOutcome.UNKNOWN
            submitted.suppressedRecipients.isNullOrEmpty() -> MailSendOutcome.SENT
            else -> MailSendOutcome.SUPPRESSED
        }
    }
}
