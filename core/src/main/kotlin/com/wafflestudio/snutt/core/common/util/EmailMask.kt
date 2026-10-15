package com.wafflestudio.snutt.core.common.util

object EmailMask {
    private val maskRegex = Regex("(?<=.{3}).(?=.*@)")

    fun mask(email: String): String = email.replace(maskRegex, "*")
}
