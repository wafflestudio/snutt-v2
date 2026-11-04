package com.wafflestudio.snutt.core.common.push

import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URLEncoder

enum class DeeplinkType(
    val path: String,
) {
    NOTIFICATIONS("notifications"),
    VACANCY("vacancy"),
    FRIENDS("friends?openDrawer=true"),
    TIMETABLE_LECTURE("timetable-lecture?timetableId=%s&lectureId=%s"),
    BOOKMARKS("bookmarks?year=%s&semester=%s&lectureId=%s"),
    DIARY("diary?lectureId=%s&courseTitle=%s"),
}

@Component
class DeeplinkResolver(
    private val environment: Environment,
) {
    private val scheme = if (environment.matchesProfiles("prod")) "snutt" else "snutt-dev"

    fun build(
        type: DeeplinkType,
        vararg params: Any,
    ): String {
        val encodedParams = params.map { encode(it.toString()) }.toTypedArray()
        return "$scheme://${type.path.format(*encodedParams)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
