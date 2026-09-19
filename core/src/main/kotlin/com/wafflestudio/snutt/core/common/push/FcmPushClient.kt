package com.wafflestudio.snutt.core.common.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.ApsAlert
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
    @param:Value("\${snutt.fcm.ios-bundle-id:}") private val iosBundleId: String,
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
                            MessagingErrorCode.UNREGISTERED ->
                                invalidRegistrationIds += chunk[index].fcmRegistrationId
                            else -> Unit
                        }
                    }
                }.onFailure { log.error("푸시 전송 실패", it) }
            }
        return PushSendResult(invalidRegistrationIds)
    }

    override fun sendTopicMessage(
        topic: String,
        message: PushMessage,
    ) {
        runCatching {
            FirebaseMessaging.getInstance().send(
                Message
                    .builder()
                    .setTopic(topic)
                    .withPayload(message)
                    .build(),
            )
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

    @Suppress("DEPRECATION")
    private fun TargetedPushMessage.toFcmMessage(): Message =
        Message
            .builder()
            .setToken(fcmRegistrationId)
            .withPayload(message)
            .build()

    private fun Message.Builder.withPayload(message: PushMessage): Message.Builder {
        setAndroidConfig(
            AndroidConfig
                .builder()
                .setPriority(if (message.isUrgentOnAndroid) AndroidConfig.Priority.HIGH else AndroidConfig.Priority.NORMAL)
                .apply {
                    message.urlScheme?.let { setNotification(AndroidNotification.builder().setClickAction(it).build()) }
                }.build(),
        )
        setApnsConfig(
            ApnsConfig
                .builder()
                .putHeader("apns-push-type", "alert")
                .putHeader("apns-priority", "5")
                .apply { if (iosBundleId.isNotBlank()) putHeader("apns-topic", iosBundleId) }
                .setAps(
                    Aps
                        .builder()
                        .setAlert(
                            ApsAlert
                                .builder()
                                .setTitle(message.title)
                                .setBody(message.body)
                                .build(),
                        ).setContentAvailable(true)
                        .build(),
                ).build(),
        )
        if (message.shouldSendAsDataMessage) {
            putData(TITLE_KEY, message.title)
            putData(BODY_KEY, message.body)
        } else {
            setNotification(
                Notification
                    .builder()
                    .setTitle(message.title)
                    .setBody(message.body)
                    .build(),
            )
        }
        message.urlScheme?.let { putData(URL_SCHEME_KEY, it) }
        putAllData(message.data)
        return this
    }

    companion object {
        private const val FCM_MESSAGE_COUNT_LIMIT = 500
        private const val TITLE_KEY = "title"
        private const val BODY_KEY = "body"
        private const val URL_SCHEME_KEY = "url_scheme"
    }
}
