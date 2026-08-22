package com.wafflestudio.snutt.core.common.push

interface PushClient {
    fun sendMessages(messages: List<TargetedPushMessage>): PushSendResult

    fun sendTopicMessage(message: TopicPushMessage)

    fun subscribeGlobalTopic(registrationId: String)

    fun unsubscribeGlobalTopic(registrationId: String)
}

data class TargetedPushMessage(
    val title: String,
    val body: String,
    val urlScheme: String?,
    val fcmRegistrationId: String,
)

data class TopicPushMessage(
    val title: String,
    val body: String,
    val urlScheme: String?,
    val topic: String,
)

data class PushSendResult(
    val invalidRegistrationIds: List<String> = emptyList(),
)

/** 구버전 백엔드와 동일한 이름이어야 기존 기기의 토픽 구독이 그대로 이어진다. */
const val GLOBAL_TOPIC = "global"
