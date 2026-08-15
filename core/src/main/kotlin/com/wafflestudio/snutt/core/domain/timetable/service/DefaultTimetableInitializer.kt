package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.user.event.UserRegisteredEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class DefaultTimetableInitializer(
    private val timetableService: TimetableService,
    private val coursebookService: CoursebookService,
) {
    @EventListener
    fun createDefaultTimetable(event: UserRegisteredEvent) {
        if (coursebookService.findLatestCoursebook() == null) return
        val timetable = timetableService.createDefaultTable(event.userId)
        timetableService.setPrimary(event.userId, timetable.externalId)
    }
}
