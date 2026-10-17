package com.wafflestudio.snutt.core.common.client

data class LocalizedMessage(
    val ko: String,
    val en: String,
) {
    fun of(language: Language): String = if (language == Language.EN) en else ko
}

infix fun String.en(en: String): LocalizedMessage = LocalizedMessage(this, en)
