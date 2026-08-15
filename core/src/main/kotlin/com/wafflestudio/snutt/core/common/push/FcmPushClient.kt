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
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("!test")
class FcmPushClient(
    @param:Value("\${snutt.fcm.service-account}") serviceAccountJson: String,
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

    override fun sendMessages(messages: List<TargetedPushMessage>) {
        val messaging = FirebaseMessaging.getInstance()
        messages
            .chunked(FCM_MESSAGE_COUNT_LIMIT)
            .forEach { chunk ->
                runCatching {
                    messaging.sendEach(
                        chunk.map { it.toFcmMessage() },
                    )
                }.onFailure { log.error("푸시 전송 실패", it) }
            }
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

    private fun TargetedPushMessage.toFcmMessage(): Message {
        val builder =
            Message
                .builder()
                .setToken(fcmRegistrationId)
                .setNotification(
                    Notification
                        .builder()
                        .setTitle(title)
                        .setBody(body)
                        .build(),
                )
        urlScheme?.let { scheme ->
            builder.setAndroidConfig(
                AndroidConfig
                    .builder()
                    .setNotification(AndroidNotification.builder().setClickAction(scheme).build())
                    .build(),
            )
            builder.setApnsConfig(
                ApnsConfig
                    .builder()
                    .setAps(Aps.builder().setCategory(scheme).build())
                    .build(),
            )
        }
        return builder.build()
    }

    companion object {
        private const val FCM_MESSAGE_COUNT_LIMIT = 500
        private const val GLOBAL_TOPIC = "global"
    }
}
