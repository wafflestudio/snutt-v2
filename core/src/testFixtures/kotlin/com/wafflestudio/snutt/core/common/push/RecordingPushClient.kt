package com.wafflestudio.snutt.core.common.push

import org.springframework.stereotype.Service
import java.util.concurrent.CopyOnWriteArrayList

@Service
class RecordingPushClient : PushClient {
    val sentMessages: MutableList<TargetedPushMessage> = CopyOnWriteArrayList()

    val topicMessages: MutableList<Pair<String, PushMessage>> = CopyOnWriteArrayList()

    val globalTopicSubscriptions: MutableList<String> = CopyOnWriteArrayList()

    override fun sendMessages(messages: List<TargetedPushMessage>): PushSendResult {
        sentMessages.addAll(messages)
        return PushSendResult()
    }

    override fun sendTopicMessage(
        topic: String,
        message: PushMessage,
    ) {
        topicMessages.add(topic to message)
    }

    override fun subscribeGlobalTopic(registrationId: String) {
        globalTopicSubscriptions.add(registrationId)
    }

    override fun unsubscribeGlobalTopic(registrationId: String) {
        globalTopicSubscriptions.remove(registrationId)
    }
}
