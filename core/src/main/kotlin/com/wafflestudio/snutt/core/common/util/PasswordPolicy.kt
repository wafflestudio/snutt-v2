package com.wafflestudio.snutt.core.common.util

object PasswordPolicy {
    val passwordRegex = Regex("""^(?=.*\d)(?=.*[a-zA-Z])\S{6,20}$""")
    val localIdRegex = Regex("""^[a-zA-Z0-9]{4,32}$""")

    fun isValidPassword(password: String): Boolean = password.matches(passwordRegex)
}
