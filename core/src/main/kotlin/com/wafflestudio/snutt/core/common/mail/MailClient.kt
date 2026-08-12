package com.wafflestudio.snutt.core.common.mail

import org.springframework.context.annotation.Profile
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

enum class MailType(
    val subject: String,
) {
    VERIFICATION("[SNUTT] 이메일 인증 코드"),
    PASSWORD_RESET("[SNUTT] 비밀번호 초기화 인증 코드"),
}

interface MailClient {
    fun sendCodeMail(
        type: MailType,
        to: String,
        code: String,
    )
}

// SMTP 발송 (spring-boot-starter-mail). 운영은 spring.mail.* 환경변수로 구성한다
@Service
@Profile("!test")
class SmtpMailClient(
    private val mailSender: JavaMailSender,
) : MailClient {
    override fun sendCodeMail(
        type: MailType,
        to: String,
        code: String,
    ) {
        val message =
            SimpleMailMessage().apply {
                setTo(to)
                setSubject(type.subject)
                setText("SNUTT 인증 코드는 $code 입니다.")
            }
        mailSender.send(message)
    }
}

// 테스트 프로파일 전용 메일 대역. 발송 내용을 기록만 한다
@Service
@Profile("test")
class RecordingMailClient : MailClient {
    val sentMails: MutableList<Pair<String, String>> = java.util.concurrent.CopyOnWriteArrayList()

    override fun sendCodeMail(
        type: MailType,
        to: String,
        code: String,
    ) {
        sentMails.add(to to code)
    }
}
