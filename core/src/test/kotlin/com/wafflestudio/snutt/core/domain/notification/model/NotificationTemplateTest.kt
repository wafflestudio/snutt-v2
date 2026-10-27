package com.wafflestudio.snutt.core.domain.notification.model

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.LocalizedMessage
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

    @Test
    fun renderLocalizesBothLanguagesAndItsArguments() {
        val courseTitle = LocalizedMessage(ko = "컴퓨터프로그래밍", en = "Computer Programming")
        val message = NotificationTemplate.LECTURE_VACANCY.render(courseTitle, "001", LocalizedMessage.same("14:00"))

        assertThat(message.of(Language.KO).title).isEqualTo("빈자리 알림")
        assertThat(message.of(Language.KO).body)
            .isEqualTo("\"컴퓨터프로그래밍 (001)\" 강의에 빈자리가 생겼습니다.\n14:00에 수강신청 사이트를 확인해보세요!")
        assertThat(message.of(Language.EN).title).isEqualTo("Vacancy alert")
        assertThat(message.of(Language.EN).body)
            .isEqualTo("A seat has opened up in \"Computer Programming (001)\".\nCheck the course registration site at 14:00!")
    }

    companion object {
        private const val HANGUL = "[가-힣]"
        private val PLACEHOLDER = Regex("%[a-zA-Z]")
    }
}
