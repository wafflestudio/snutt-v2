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

// 읽기 시점 언어 선택 + 폴백. EN이면 영문 우선(없으면 한글), KO면 항상 한글
fun Language.select(
    ko: String,
    en: String?,
): String = if (this == Language.EN) en ?: ko else ko

@JvmName("selectNullable")
fun Language.select(
    ko: String?,
    en: String?,
): String? = if (this == Language.EN) en ?: ko else ko
