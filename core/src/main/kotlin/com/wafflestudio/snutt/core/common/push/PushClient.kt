package com.wafflestudio.snutt.core.common.push

// FCM 발송 추상화. 테스트에서는 fake로 교체한다 (v1 @Profile("!test") 패턴)
interface PushClient {
    fun sendMessages(messages: List<TargetedPushMessage>)
}

data class TargetedPushMessage(
    val title: String,
    val body: String,
    val urlScheme: String?,
    val fcmRegistrationId: String,
)
