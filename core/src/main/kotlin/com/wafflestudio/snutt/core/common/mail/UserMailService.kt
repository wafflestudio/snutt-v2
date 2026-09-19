package com.wafflestudio.snutt.core.common.mail

import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service

enum class UserMailType {
    VERIFICATION,
    PASSWORD_RESET,
    FIND_ID,
}

@Service
class UserMailService(
    private val mailClient: MailClient,
    resourceLoader: ResourceLoader,
) {
    private data class Template(
        val subject: String,
        val body: String,
    )

    private val templates: Map<UserMailType, Template> =
        resourceLoader
            .getResource("classpath:userMailTemplate.txt")
            .inputStream
            .readBytes()
            .decodeToString()
            .split("\n\n")
            .chunked(2)
            .map { (subject, body) -> Template(subject.trim(), body.trim()) }
            .let { UserMailType.entries.zip(it).toMap() }

    fun sendVerificationCode(
        to: String,
        code: String,
    ) = send(UserMailType.VERIFICATION, to, mapOf("code" to code))

    fun sendPasswordResetCode(
        to: String,
        code: String,
    ) = send(UserMailType.PASSWORD_RESET, to, mapOf("code" to code))

    fun sendFoundAccounts(
        to: String,
        accountInfo: String,
    ) = send(UserMailType.FIND_ID, to, mapOf("email" to to, "accountInfo" to accountInfo))

    private fun send(
        type: UserMailType,
        to: String,
        values: Map<String, String>,
    ) {
        val template = templates.getValue(type)
        mailClient.send(to, template.subject.fill(values), template.body.fill(values))
    }

    private fun String.fill(values: Map<String, String>): String =
        values.entries.fold(this) { text, (key, value) -> text.replace("{$key}", value) }
}
