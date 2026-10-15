package com.wafflestudio.snutt.core.common.error

import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.LocalizedMessage
import org.springframework.dao.DataIntegrityViolationException

open class SnuttException(
    val error: ErrorType,
    vararg args: Any,
    cause: Throwable? = null,
) : RuntimeException(cause) {
    private val args: Array<out Any> = args

    override val message: String = displayMessage(Language.KO)

    fun displayMessage(language: Language): String {
        val localized = args.map { if (it is LocalizedMessage) it.of(language) else it }
        return error.displayMessage.of(language).format(*localized.toTypedArray())
    }
}

inline fun <T> conflictAs(
    errorType: ErrorType,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: DataIntegrityViolationException) {
        throw SnuttException(errorType)
    }
