package com.wafflestudio.snutt.core.common.error

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ErrorTypeTest {
    @Test
    fun englishMessagesAreFilledAndFreeOfHangul() {
        ErrorType.entries.forEach { error ->
            listOf("displayMessage" to error.displayMessage.en, "title" to error.title.en).forEach { (field, value) ->
                assertThat(value)
                    .describedAs("${error.name}.$field")
                    .isNotBlank()
                    .doesNotContainPattern(HANGUL)
            }
        }
    }

    @Test
    fun formatPlaceholderCountsMatchBetweenLanguages() {
        ErrorType.entries.forEach { error ->
            listOf("displayMessage" to error.displayMessage, "title" to error.title).forEach { (field, message) ->
                assertThat(PLACEHOLDER.findAll(message.en).count())
                    .describedAs("${error.name}.$field")
                    .isEqualTo(PLACEHOLDER.findAll(message.ko).count())
            }
        }
    }

    companion object {
        private const val HANGUL = "[가-힣]"
        private val PLACEHOLDER = Regex("%[a-zA-Z]")
    }
}
