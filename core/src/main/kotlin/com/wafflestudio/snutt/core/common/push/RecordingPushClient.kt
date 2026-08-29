package com.wafflestudio.snutt.core.common.push

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.concurrent.CopyOnWriteArrayList

@Service
@Profile("test")
class RecordingPushClient : PushClient {
    val sentMessages: MutableList<TargetedPushMessage> = CopyOnWriteArrayList()

    val topicMessages: MutableList<TopicPushMessage> = CopyOnWriteArrayList()

    val globalTopicSubscriptions: MutableList<String> = CopyOnWriteArrayList()

    override fun sendMessages(messages: List<TargetedPushMessage>): PushSendResult {
        sentMessages.addAll(messages)
        return PushSendResult()
    }

    override fun sendTopicMessage(message: TopicPushMessage) {
        topicMessages.add(message)
    }

    override fun subscribeGlobalTopic(registrationId: String) {
        globalTopicSubscriptions.add(registrationId)
    }

    override fun unsubscribeGlobalTopic(registrationId: String) {
        globalTopicSubscriptions.remove(registrationId)
    }
}
