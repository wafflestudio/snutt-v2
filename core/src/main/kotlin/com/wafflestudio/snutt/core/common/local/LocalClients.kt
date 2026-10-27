package com.wafflestudio.snutt.core.common.local

import com.wafflestudio.snutt.core.common.mail.MailClient
import com.wafflestudio.snutt.core.common.mail.MailSendOutcome
import com.wafflestudio.snutt.core.common.push.PushClient
import com.wafflestudio.snutt.core.common.push.PushMessage
import com.wafflestudio.snutt.core.common.push.PushSendResult
import com.wafflestudio.snutt.core.common.push.TargetedPushMessage
import com.wafflestudio.snutt.core.common.storage.FileUploadUri
import com.wafflestudio.snutt.core.common.storage.StorageSource
import com.wafflestudio.snutt.core.common.storage.UploadUriIssuer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger("LocalClients")

@Component
@Profile("local")
class LocalMailClient : MailClient {
    override fun send(
        to: String,
        subject: String,
        html: String,
    ): MailSendOutcome {
        log.info("mail to={} subject={}\n{}", to, subject, html)
        return MailSendOutcome.SENT
    }
}

@Component
@Profile("local")
class LocalPushClient : PushClient {
    override fun sendMessages(messages: List<TargetedPushMessage>): PushSendResult {
        log.info("push {}", messages)
        return PushSendResult()
    }

    override fun sendTopicMessage(
        topic: String,
        message: PushMessage,
    ) = log.info("push topic={} {}", topic, message)

    override fun subscribeGlobalTopic(registrationId: String) = Unit

    override fun unsubscribeGlobalTopic(registrationId: String) = Unit
}

@Component
@Profile("local")
class LocalUploadUriIssuer : UploadUriIssuer {
    override fun issue(
        source: StorageSource,
        count: Int,
    ): List<FileUploadUri> = error("local profile은 업로드 URI 발급을 지원하지 않는다")
}
