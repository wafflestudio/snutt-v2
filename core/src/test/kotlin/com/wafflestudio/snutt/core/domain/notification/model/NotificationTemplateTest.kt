package com.wafflestudio.snutt.core.domain.notification.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotificationTemplateTest {
    @Test
    fun englishMessagesAreFilledAndFreeOfHangul() {
        NotificationTemplate.entries.forEach { template ->
            listOf("title" to template.title.en, "body" to template.body.en).forEach { (field, value) ->
                assertThat(value)
                    .describedAs("${template.name}.$field")
                    .isNotBlank()
                    .doesNotContainPattern(HANGUL)
            }
        }
    }

    @Test
    fun formatPlaceholderCountsMatchBetweenLanguages() {
        NotificationTemplate.entries.forEach { template ->
            listOf("title" to template.title, "body" to template.body).forEach { (field, message) ->
                assertThat(PLACEHOLDER.findAll(message.en).count())
                    .describedAs("${template.name}.$field")
                    .isEqualTo(PLACEHOLDER.findAll(message.ko).count())
            }
        }
    }

    companion object {
        private const val HANGUL = "[가-힣]"
        private val PLACEHOLDER = Regex("%[a-zA-Z]")
    }
}
