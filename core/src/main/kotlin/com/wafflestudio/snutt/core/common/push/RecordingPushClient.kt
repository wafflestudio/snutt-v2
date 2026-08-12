package com.wafflestudio.snutt.core.common.push

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.concurrent.CopyOnWriteArrayList

// 테스트 프로파일 전용 FCM 대역. 발송 요청을 기록만 하고 실제 전송은 하지 않는다
@Service
@Profile("test")
class RecordingPushClient : PushClient {
    val sentMessages: MutableList<TargetedPushMessage> = CopyOnWriteArrayList()

    val globalTopicSubscriptions: MutableList<String> = CopyOnWriteArrayList()

    override fun sendMessages(messages: List<TargetedPushMessage>) {
        sentMessages.addAll(messages)
    }

    override fun subscribeGlobalTopic(registrationId: String) {
        globalTopicSubscriptions.add(registrationId)
    }

    override fun unsubscribeGlobalTopic(registrationId: String) {
        globalTopicSubscriptions.remove(registrationId)
    }
}
