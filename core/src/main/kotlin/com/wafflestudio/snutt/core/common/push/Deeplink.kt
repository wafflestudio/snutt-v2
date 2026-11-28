package com.wafflestudio.snutt.core.common.push

import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
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
        val timetable: Timetable,
        val lecture: Lecture,
    ) : Deeplink {
        override val path = "timetable-lecture"
        override val query get() = mapOf("timetableIdV2" to checkNotNull(timetable.id), "lectureIdV2" to checkNotNull(lecture.id))
    }

    data class Bookmarks(
        val lecture: Lecture,
    ) : Deeplink {
        override val path = "bookmarks"
        override val query get() =
            mapOf(
                "year" to lecture.year,
                "semester" to lecture.semester.name,
                "lectureIdV2" to checkNotNull(lecture.id),
            )
    }

    data class Diary(
        val lecture: Lecture,
    ) : Deeplink {
        override val path = "diary"
        override val query get() = mapOf("lectureIdV2" to checkNotNull(lecture.id), "courseTitle" to lecture.courseTitle)
    }
}

fun interface DeeplinkQueryContributor {
    fun parametersFor(deeplink: Deeplink): Map<String, Any>
}

@Component
class DeeplinkResolver(
    environment: Environment,
    private val queryContributors: List<DeeplinkQueryContributor>,
) {
    private val scheme = if (environment.matchesProfiles("prod")) "snutt" else "snutt-dev"

    fun build(deeplink: Deeplink): String {
        val parameters = queryContributors.fold(deeplink.query) { query, contributor -> query + contributor.parametersFor(deeplink) }
        val query = parameters.entries.joinToString("&") { (key, value) -> "$key=${encode(value.toString())}" }
        return "$scheme://${deeplink.path}" + if (query.isEmpty()) "" else "?$query"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
