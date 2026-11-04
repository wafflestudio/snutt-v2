package com.wafflestudio.snutt.core.common.mail

import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service

enum class UserMailType(
    val logName: String,
) {
    VERIFICATION("email-verification"),
    PASSWORD_RESET("password-reset"),
    FIND_ID("find-id"),
}

data class RenderedMail(
    val subject: String,
    val html: String,
)

@Service
class UserMailService(
    resourceLoader: ResourceLoader,
) {
    private val templates: Map<UserMailType, RenderedMail> =
        resourceLoader
            .getResource("classpath:userMailTemplate.txt")
            .inputStream
            .readBytes()
            .decodeToString()
            .split("\n\n")
            .chunked(2)
            .map { (subject, body) -> RenderedMail(subject.trim(), body.trim()) }
            .let { UserMailType.entries.zip(it).toMap() }

    fun render(
        type: UserMailType,
        values: Map<String, String>,
    ): RenderedMail {
        val template = templates.getValue(type)
        return RenderedMail(template.subject.fill(values), template.html.fill(values))
    }

    private fun String.fill(values: Map<String, String>): String =
        values.entries.fold(this) { text, (key, value) -> text.replace("{$key}", value) }
}
