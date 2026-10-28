package com.wafflestudio.snutt.core.common.client

data class LocalizedMessage(
    val ko: String,
    val en: String,
) {
    fun of(language: Language): String = if (language == Language.EN) en else ko

    fun format(vararg args: Any): LocalizedMessage =
        LocalizedMessage(
            ko = ko.format(*args.localizeArgs(Language.KO)),
            en = en.format(*args.localizeArgs(Language.EN)),
        )

    companion object {
        fun same(value: String): LocalizedMessage = LocalizedMessage(ko = value, en = value)
    }
}

fun Array<out Any>.localizeArgs(language: Language): Array<Any> = map { if (it is LocalizedMessage) it.of(language) else it }.toTypedArray()

fun List<LocalizedMessage>.joinLocalized(separator: String = ", "): LocalizedMessage =
    LocalizedMessage(ko = joinToString(separator) { it.ko }, en = joinToString(separator) { it.en })
