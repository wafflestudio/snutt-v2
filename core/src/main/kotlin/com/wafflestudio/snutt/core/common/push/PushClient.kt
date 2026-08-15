package com.wafflestudio.snutt.core.common.push

interface PushClient {
    fun sendMessages(messages: List<TargetedPushMessage>)

    fun subscribeGlobalTopic(registrationId: String)

    fun unsubscribeGlobalTopic(registrationId: String)
}

data class TargetedPushMessage(
    val title: String,
    val body: String,
    val urlScheme: String?,
    val fcmRegistrationId: String,
)
