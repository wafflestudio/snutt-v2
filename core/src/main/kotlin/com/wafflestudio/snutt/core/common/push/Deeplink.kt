package com.wafflestudio.snutt.core.common.push

import com.wafflestudio.snutt.core.common.enums.Semester
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URLEncoder

sealed interface Deeplink {
    val path: String
    val query: Map<String, Any> get() = emptyMap()

    data object Notifications : Deeplink {
        override val path = "notifications"
    }

    data object Vacancy : Deeplink {
        override val path = "vacancy"
    }

    data object Friends : Deeplink {
        override val path = "friends"
        override val query = mapOf("openDrawer" to true)
    }

    data class TimetableLecture(
        val timetableId: Long,
        val lectureId: Long,
    ) : Deeplink {
        override val path = "timetable-lecture"
        override val query get() = mapOf("timetableId" to timetableId, "lectureId" to lectureId)
    }

    data class Bookmarks(
        val year: Int,
        val semester: Semester,
        val lectureId: Long,
    ) : Deeplink {
        override val path = "bookmarks"
        override val query get() = mapOf("year" to year, "semester" to semester.name, "lectureId" to lectureId)
    }

    data class Diary(
        val lectureId: Long,
        val courseTitle: String,
    ) : Deeplink {
        override val path = "diary"
        override val query get() = mapOf("lectureId" to lectureId, "courseTitle" to courseTitle)
    }
}

@Component
class DeeplinkResolver(
    environment: Environment,
) {
    private val scheme = if (environment.matchesProfiles("prod")) "snutt" else "snutt-dev"

    fun build(deeplink: Deeplink): String {
        val query = deeplink.query.entries.joinToString("&") { (key, value) -> "$key=${encode(value.toString())}" }
        return "$scheme://${deeplink.path}" + if (query.isEmpty()) "" else "?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
