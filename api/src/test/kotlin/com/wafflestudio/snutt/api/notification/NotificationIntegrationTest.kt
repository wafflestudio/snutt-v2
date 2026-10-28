package com.wafflestudio.snutt.api.notification

import com.wafflestudio.snutt.api.AbstractApiIntegrationTest
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import com.wafflestudio.snutt.core.common.push.LocalizedPushMessage
import com.wafflestudio.snutt.core.common.push.PushMessage
import com.wafflestudio.snutt.core.domain.notification.model.Notification
import com.wafflestudio.snutt.core.domain.notification.model.NotificationType
import com.wafflestudio.snutt.core.domain.notification.repository.NotificationRepository
import com.wafflestudio.snutt.core.domain.notification.service.PushService
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired

class NotificationIntegrationTest : AbstractApiIntegrationTest() {
    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var pushService: PushService

    @Test
    fun `알림함은 요청 언어로 문구를 고르고 영문이 없으면 한글로 돌아간다`() {
        val token = register("notiuser")
        val userId = userRepository.findByLocalIdAndActiveTrue("notiuser")!!.id!!
        notificationRepository.save(
            Notification(
                userId = userId,
                title = "빈자리 알림",
                titleEn = "Vacancy alert",
                message = "\"고급한국어 (001)\" 강의에 빈자리가 생겼습니다.",
                messageEn = "A seat has opened up in \"Advanced Korean (001)\".",
                type = NotificationType.LECTURE_VACANCY,
            ),
        )
        notificationRepository.save(Notification(userId = userId, title = "공지", message = "내용"))

        val ko = body(get("/v2/notifications", token, Language.KO))["content"]
        val en = body(get("/v2/notifications", token, Language.EN))["content"]

        assertEquals("빈자리 알림", ko[1]["title"].asString())
        assertEquals("\"고급한국어 (001)\" 강의에 빈자리가 생겼습니다.", ko[1]["message"].asString())
        assertEquals("Vacancy alert", en[1]["title"].asString())
        assertEquals("A seat has opened up in \"Advanced Korean (001)\".", en[1]["message"].asString())

        assertEquals("공지", ko[0]["title"].asString())
        assertEquals("공지", en[0]["title"].asString())
        assertEquals("내용", en[0]["message"].asString())
    }

    @Test
    fun `전체 공지는 언어별 토픽으로 각각 발행되고 알림함에 ko en이 함께 쌓인다`() {
        pushService.sendGlobalPushAndNotification(
            LocalizedPushMessage(
                title = LocalizedMessage(ko = "신규 수강편람", en = "New course book"),
                body = LocalizedMessage(ko = "2026년도 2학기 수강편람이 추가되었습니다.", en = "The 2026 Fall course book has been added."),
            ),
            NotificationType.COURSEBOOK,
        )

        verify(pushClient).sendTopicMessage("global", PushMessage(title = "신규 수강편람", body = "2026년도 2학기 수강편람이 추가되었습니다."))
        verify(pushClient).sendTopicMessage(
            "global_en",
            PushMessage(title = "New course book", body = "The 2026 Fall course book has been added."),
        )

        val saved = notificationRepository.findAll().single()
        assertEquals("신규 수강편람", saved.title)
        assertEquals("New course book", saved.titleEn)
        assertEquals("2026년도 2학기 수강편람이 추가되었습니다.", saved.message)
        assertEquals("The 2026 Fall course book has been added.", saved.messageEn)
    }
}
