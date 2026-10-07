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

interface MailClient {
    fun send(
        to: String,
        subject: String,
        html: String,
    )
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
    ) {
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
        client.submitEmail(SubmitEmailRequest.builder().submitEmailDetails(details).build())
    }
}
