package com.wafflestudio.snutt.core.common.util

object CopyTitle {
    private val copyNumberSuffix = """\s\(\d+\)$""".toRegex()

    fun next(
        title: String,
        existingTitles: Collection<String>,
    ): String {
        val base = title.replace(copyNumberSuffix, "")
        val numbered = Regex("^${Regex.escape(base)} \\((\\d+)\\)$")
        val last =
            existingTitles
                .mapNotNull {
                    numbered
                        .matchEntire(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                }.maxOrNull() ?: 0
        return "$base (${last + 1})"
    }
}
