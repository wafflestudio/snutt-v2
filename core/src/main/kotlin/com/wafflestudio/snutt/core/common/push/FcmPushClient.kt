package com.wafflestudio.snutt.core.common.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("!test")
class FcmPushClient(
    @Value("\${snutt.fcm.service-account}") serviceAccountJson: String,
) : PushClient {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        val options =
            FirebaseOptions
                .builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccountJson.byteInputStream()))
                .build()
        FirebaseApp.initializeApp(options)
    }

    override fun sendMessages(messages: List<TargetedPushMessage>): PushSendResult {
        val messaging = FirebaseMessaging.getInstance()
        val invalidRegistrationIds = mutableListOf<String>()
        messages
            .chunked(FCM_MESSAGE_COUNT_LIMIT)
            .forEach { chunk ->
                runCatching {
                    val response = messaging.sendEach(chunk.map { it.toFcmMessage() })
                    response.responses.forEachIndexed { index, sendResponse ->
                        if (sendResponse.isSuccessful) return@forEachIndexed
                        when (sendResponse.exception?.messagingErrorCode) {
                            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT ->
                                invalidRegistrationIds += chunk[index].fcmRegistrationId
                            else -> Unit
                        }
                    }
                }.onFailure { log.error("푸시 전송 실패", it) }
            }
        return PushSendResult(invalidRegistrationIds)
    }

    override fun sendTopicMessage(message: TopicPushMessage) {
        runCatching {
            FirebaseMessaging.getInstance().send(message.toFcmTopicMessage())
        }.onFailure { log.error("토픽 푸시 전송 실패", it) }
    }

    override fun subscribeGlobalTopic(registrationId: String) {
        runCatching {
            FirebaseMessaging.getInstance().subscribeToTopic(listOf(registrationId), GLOBAL_TOPIC)
        }.onFailure { log.error("글로벌 토픽 구독 실패", it) }
    }

    override fun unsubscribeGlobalTopic(registrationId: String) {
        runCatching {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(listOf(registrationId), GLOBAL_TOPIC)
        }.onFailure { log.error("글로벌 토픽 구독 해제 실패", it) }
    }

    private fun TopicPushMessage.toFcmTopicMessage(): Message {
        val builder =
            Message
                .builder()
                .setTopic(topic)
        return builder.buildNotification(title, body, urlScheme).build()
    }

    // setFid 전환은 클라이언트가 FID를 등록해야 가능하다. 저장된 값은 registration token이므로 setToken을 유지한다
    @Suppress("DEPRECATION")
    private fun TargetedPushMessage.toFcmMessage(): Message {
        val builder =
            Message
                .builder()
                .setToken(fcmRegistrationId)
        return builder.buildNotification(title, body, urlScheme).build()
    }

    private fun Message.Builder.buildNotification(
        title: String,
        body: String,
        urlScheme: String?,
    ): Message.Builder {
        setNotification(
            Notification
                .builder()
                .setTitle(title)
                .setBody(body)
                .build(),
        )
        urlScheme?.let { scheme ->
            setAndroidConfig(
                AndroidConfig
                    .builder()
                    .setNotification(AndroidNotification.builder().setClickAction(scheme).build())
                    .build(),
            )
            setApnsConfig(
                ApnsConfig
                    .builder()
                    .setAps(Aps.builder().setCategory(scheme).build())
                    .build(),
            )
        }
        return this
    }

    companion object {
        private const val FCM_MESSAGE_COUNT_LIMIT = 500
    }
}
