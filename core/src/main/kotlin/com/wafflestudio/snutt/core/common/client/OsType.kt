package com.wafflestudio.snutt.core.common.client

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class OsType(
    @JsonValue val value: String,
) {
    IOS("ios"),
    ANDROID("android"),
    WEB("web"),
    ;

    companion object {
        private val valueMap = entries.associateBy { it.value }

        @JsonCreator
        @JvmStatic
        fun fromValue(value: String): OsType = from(value) ?: throw IllegalArgumentException("unknown os type: $value")

        fun from(value: String?): OsType? = value?.lowercase()?.let(valueMap::get)
    }
}
