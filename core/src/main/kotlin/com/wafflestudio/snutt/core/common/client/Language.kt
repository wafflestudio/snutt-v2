package com.wafflestudio.snutt.core.common.client

enum class Language {
    KO,
    EN,
    ;

    companion object {
        fun from(value: String?): Language? =
            value?.lowercase()?.let { lang ->
                entries.firstOrNull { it.name.lowercase() == lang }
            }
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
