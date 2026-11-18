package com.wafflestudio.snutt.core.common.search

object EnglishSearchQuery {
    const val MAX_EDGE_WORDS = 3

    const val MIN_INSTRUCTOR_LETTERS = 2

    const val MAX_SEQUENCE_CHARS = 40

    private const val NON_WORD = "[^A-Za-z0-9]"

    private const val INSTRUCTOR_SEPARATOR = "[^A-Za-z0-9가-힣]"

    private val courseNumberRegex = """^[A-Za-z]{0,2}\d{3,5}\.[0-9A-Za-z]{3,6}$""".toRegex()
    private val lectureNumberRegex = """^\d{3}$""".toRegex()

    sealed interface Cond {
        data class Title(
            val from: Int,
            val to: Int,
        ) : Cond

        data class Instructor(
            val index: Int,
        ) : Cond

        data class And(
            val left: Cond,
            val right: Cond,
        ) : Cond

        data class Or(
            val items: List<Cond>,
        ) : Cond
    }

    fun normalize(token: String): String = token.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    fun isNumberToken(token: String): Boolean = courseNumberRegex.matches(token) || lectureNumberRegex.matches(token)

    fun isTooLong(tokens: List<String>): Boolean = tokens.sumOf { it.length } > MAX_SEQUENCE_CHARS

    fun titlePattern(tokens: List<String>): String {
        require(tokens.isNotEmpty() && tokens.all { it.isNormalized() }) { "normalize된 토큰만 받는다: $tokens" }
        return buildString {
            append("(?:^|$NON_WORD)")
            tokens.forEachIndexed { i, token ->
                if (i > 0) append(".*$NON_WORD")
                token.forEachIndexed { j, ch ->
                    if (j > 0) append("(?:.*$NON_WORD)?")
                    append(ch)
                }
            }
        }
    }

    fun instructorPattern(token: String): String {
        require(token.isNormalized()) { "normalize된 토큰만 받는다: $token" }
        return token.toList().joinToString("$INSTRUCTOR_SEPARATOR*", prefix = "(?:^|$INSTRUCTOR_SEPARATOR)")
    }

    fun condition(tokens: List<String>): Cond {
        val m = tokens.size
        require(m > 0) { "영어 토큰이 없다" }

        fun instructorAllowed(index: Int) = tokens[index].length >= MIN_INSTRUCTOR_LETTERS

        fun prefix(k: Int): Cond? {
            if (k > minOf(MAX_EDGE_WORDS, m) || !instructorAllowed(k - 1)) return null
            val instructor = Cond.Instructor(k - 1)
            if (k == m) return instructor
            return Cond.And(instructor, or(listOfNotNull(Cond.Title(k, m), prefix(k + 1))))
        }

        fun suffix(k: Int): Cond? {
            if (k > minOf(MAX_EDGE_WORDS, m - 1) || !instructorAllowed(m - k)) return null
            return Cond.And(Cond.Instructor(m - k), or(listOfNotNull(Cond.Title(0, m - k), suffix(k + 1))))
        }

        return or(listOfNotNull(Cond.Title(0, m), prefix(1), suffix(1)))
    }

    fun <T> Cond.fold(
        tokens: List<String>,
        title: (pattern: String) -> T,
        instructor: (pattern: String) -> T,
        and: (T, T) -> T,
        or: (List<T>) -> T,
    ): T =
        when (this) {
            is Cond.Title -> title(titlePattern(tokens.subList(from, to)))
            is Cond.Instructor -> instructor(instructorPattern(tokens[index]))
            is Cond.And -> and(left.fold(tokens, title, instructor, and, or), right.fold(tokens, title, instructor, and, or))
            is Cond.Or -> or(items.map { it.fold(tokens, title, instructor, and, or) })
        }

    private fun or(items: List<Cond>): Cond = items.singleOrNull() ?: Cond.Or(items)

    private fun String.isNormalized(): Boolean = isNotEmpty() && all { it in 'a'..'z' || it in '0'..'9' }
}
