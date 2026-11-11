package com.wafflestudio.snutt.api.user

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.api.testutil.legacyApiKey
import com.wafflestudio.snutt.core.domain.maillog.service.MailLogService
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

class MailLogIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var mailLogService: MailLogService

    private lateinit var adminToken: String

    @BeforeEach
    fun seed() {
        adminToken = register("mailadmin")
        userRepository.findByLocalIdAndActiveTrue("mailadmin")!!.let { user ->
            user.isAdmin = true
            userRepository.save(user)
        }
    }

    private fun mailLogs(query: String): List<JsonNode> = body(get("/v2/admin/mail-logs?$query", adminToken)).toList()

    private fun v1MailLogs(query: String): List<JsonNode> {
        val login =
            client()
                .post()
                .uri("/v1/auth/login_local")
                .header("x-access-apikey", legacyApiKey())
                .body("""{"id":"mailadmin","password":"password1"}""")
                .retrieve()
                .toEntity(String::class.java)
        val response =
            client()
                .get()
                .uri("/v1/admin/mailLogs?$query")
                .header("x-access-apikey", legacyApiKey())
                .header("x-access-token", body(login)["token"].asString())
                .retrieve()
                .toEntity(String::class.java)
        return body(response).toList()
    }

    @Test
    fun `아이디·비밀번호 찾기 요청이 기록되고 어드민이 조회한다`() {
        register("mailuser", "mailuser@snu.ac.kr")

        post("/v2/auth/id/find", """{"email":"mailuser@snu.ac.kr"}""")
        post("/v2/auth/id/find", """{"email":"nobody@snu.ac.kr"}""")
        post("/v2/auth/password/reset/request", """{"localId":"nobody"}""")
        val longEmail = "a".repeat(300) + "@snu.ac.kr"
        assertEquals(200, post("/v2/auth/id/find", """{"email":"$longEmail"}""").statusCode.value())

        val sent = mailLogs("email=mailuser@snu.ac.kr").single()
        assertEquals("FIND_ID", sent["type"].asString())
        assertEquals("SENT", sent["status"].asString())
        assertTrue(sent["body"].asString().contains("mailuser"))
        assertEquals("NO_ACCOUNT", mailLogs("email=nobody@snu.ac.kr").single()["status"].asString())
        assertEquals("PASSWORD_RESET", mailLogs("localId=nobody").single()["type"].asString())
        assertEquals("NO_ACCOUNT", mailLogs("email=${longEmail.take(255)}").single()["status"].asString())
        assertEquals(sent["id"].asLong(), v1MailLogs("email=mailuser@snu.ac.kr").single()["id"].asString().toLong())
        assertEquals(403, get("/v2/admin/mail-logs?email=mailuser@snu.ac.kr", register("normaluser")).statusCode.value())
    }

    @Test
    fun `기록에 실패하면 메일을 보내지 않고 발송 오류는 FAILED로 남는다`() {
        register("mailuser", "mailuser@snu.ac.kr")
        register("erroruser", "erroruser@snu.ac.kr")

        jdbcTemplate.execute("RENAME TABLE mail_log TO mail_log_unavailable")
        try {
            assertEquals(500, post("/v2/auth/id/find", """{"email":"mailuser@snu.ac.kr"}""").statusCode.value())
        } finally {
            jdbcTemplate.execute("RENAME TABLE mail_log_unavailable TO mail_log")
        }
        verify(mailClient, never()).send(any(), any(), any())

        whenever(mailClient.send(any(), any(), any())).thenThrow(RuntimeException("timeout"))
        assertEquals(500, post("/v2/auth/id/find", """{"email":"erroruser@snu.ac.kr"}""").statusCode.value())
        assertEquals("FAILED", mailLogs("email=erroruser@snu.ac.kr").single()["status"].asString())
    }

    @Test
    fun `30일이 지난 메일 로그는 삭제된다`() {
        post("/v2/auth/id/find", """{"email":"old@snu.ac.kr"}""")
        post("/v2/auth/id/find", """{"email":"new@snu.ac.kr"}""")
        jdbcTemplate.update("UPDATE mail_log SET created_at = ? WHERE email = ?", Instant.now() - Duration.ofDays(31), "old@snu.ac.kr")

        assertEquals(1, mailLogService.deleteExpired())
        assertEquals(1, mailLogs("email=new@snu.ac.kr").size)
    }
}
