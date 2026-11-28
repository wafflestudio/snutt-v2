package com.wafflestudio.snutt.core.common.compat

import com.wafflestudio.snutt.core.common.push.Deeplink
import com.wafflestudio.snutt.core.common.push.DeeplinkQueryContributor
import org.springframework.stereotype.Component

@Component
class V1DeeplinkQueryContributor : DeeplinkQueryContributor {
    override fun parametersFor(deeplink: Deeplink): Map<String, Any> =
        when (deeplink) {
            is Deeplink.TimetableLecture -> mapOf("timetableId" to deeplink.timetable.idV1, "lectureId" to deeplink.lecture.idV1)
            is Deeplink.Bookmarks -> mapOf("lectureId" to deeplink.lecture.idV1)
            is Deeplink.Diary -> mapOf("lectureId" to deeplink.lecture.idV1)
            else -> emptyMap()
        }
}
