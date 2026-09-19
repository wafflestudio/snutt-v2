package com.wafflestudio.snutt.core.common.push

interface PushClient {
    fun sendMessages(messages: List<TargetedPushMessage>): PushSendResult

    fun sendTopicMessage(
        topic: String,
        message: PushMessage,
    )

    fun subscribeGlobalTopic(registrationId: String)

    fun unsubscribeGlobalTopic(registrationId: String)
}

data class PushMessage(
    val title: String,
    val body: String,
    val urlScheme: String? = null,
    val isUrgentOnAndroid: Boolean = false,
    val shouldSendAsDataMessage: Boolean = false,
    val data: Map<String, String> = emptyMap(),
)

data class TargetedPushMessage(
    val fcmRegistrationId: String,
    val message: PushMessage,
)

data class PushSendResult(
    val invalidRegistrationIds: List<String> = emptyList(),
)

const val GLOBAL_TOPIC = "global"
