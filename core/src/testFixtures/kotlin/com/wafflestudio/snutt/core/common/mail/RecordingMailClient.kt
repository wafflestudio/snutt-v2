package com.wafflestudio.snutt.core.common.mail

import org.springframework.stereotype.Service
import java.util.concurrent.CopyOnWriteArrayList

@Service
class RecordingMailClient : MailClient {
    data class SentMail(
        val to: String,
        val subject: String,
        val html: String,
    )

    val sentMails: MutableList<SentMail> = CopyOnWriteArrayList()

    override fun send(
        to: String,
        subject: String,
        html: String,
    ) {
        sentMails.add(SentMail(to, subject, html))
    }
}
