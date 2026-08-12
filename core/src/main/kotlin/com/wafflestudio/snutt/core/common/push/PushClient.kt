package com.wafflestudio.snutt.core.common.push

// FCM 발송 추상화. 테스트에서는 fake로 교체한다 (v1 @Profile("!test") 패턴)
interface PushClient {
    fun sendMessages(messages: List<TargetedPushMessage>)

    // 전체 공지 발송 대상이 되는 토픽 구독 (v1 GLOBAL_TOPIC)
    fun subscribeGlobalTopic(registrationId: String)

    fun unsubscribeGlobalTopic(registrationId: String)
}

data class TargetedPushMessage(
    val title: String,
    val body: String,
    val urlScheme: String?,
    val fcmRegistrationId: String,
)
