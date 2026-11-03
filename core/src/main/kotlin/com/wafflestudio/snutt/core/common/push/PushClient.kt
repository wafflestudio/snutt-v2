package com.wafflestudio.snutt.core.common.push

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.LocalizedMessage

interface PushClient {
    fun sendMessages(messages: List<TargetedPushMessage>): PushSendResult

    fun sendTopicMessage(
        topic: String,
        message: PushMessage,
    )

    fun subscribeGlobalTopic(
        registrationId: String,
        language: Language,
    )

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

data class LocalizedPushMessage(
    val title: LocalizedMessage,
    val body: LocalizedMessage,
    val urlScheme: String? = null,
    val isUrgentOnAndroid: Boolean = false,
    val shouldSendAsDataMessage: Boolean = false,
    val data: Map<String, String> = emptyMap(),
) {
    fun of(language: Language): PushMessage =
        PushMessage(
            title = title.of(language),
            body = body.of(language),
            urlScheme = urlScheme,
            isUrgentOnAndroid = isUrgentOnAndroid,
            shouldSendAsDataMessage = shouldSendAsDataMessage,
            data = data,
        )
}

data class TargetedPushMessage(
    val fcmRegistrationId: String,
    val message: PushMessage,
)

data class PushSendResult(
    val invalidRegistrationIds: List<String> = emptyList(),
)

const val GLOBAL_TOPIC = "global"

const val GLOBAL_TOPIC_EN = "global_en"

fun globalTopicOf(language: Language): String = if (language == Language.EN) GLOBAL_TOPIC_EN else GLOBAL_TOPIC

fun globalTopics(): List<String> = Language.entries.map(::globalTopicOf)
