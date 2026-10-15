package com.wafflestudio.snutt.core.common.client

enum class Language {
    KO,
    EN,
    ;

    companion object {
        private val valueMap = entries.associateBy { it.name.lowercase() }

        fun from(value: String?): Language? = value?.lowercase()?.let(valueMap::get)
    }
}

fun Language.select(
    ko: String,
    en: String?,
): String = if (this == Language.EN) en ?: ko else ko

@JvmName("selectNullable")
fun Language.select(
    ko: String?,
    en: String?,
): String? = if (this == Language.EN) en ?: ko else ko

fun languageOf(header: (String) -> String?): Language = Language.from(header("x-language")) ?: Language.KO
