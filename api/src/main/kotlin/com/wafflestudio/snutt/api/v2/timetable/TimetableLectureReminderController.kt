package com.wafflestudio.snutt.api.v2.timetable

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderDisplay
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TimetableLectureReminderResponse(
    val timetableLectureId: Long,
    val courseTitle: String,
    val option: TimetableLectureReminderOption,
)

data class TimetableLectureReminderModifyRequest(
    val option: TimetableLectureReminderOption,
)

@RestController
@RequestMapping("/v2/timetables/{timetableId}/lectures")
class TimetableLectureReminderController(
    private val timetableLectureReminderService: TimetableLectureReminderService,
) {
    @GetMapping("/{timetableLectureId}/reminder")
    fun getReminder(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
    ): TimetableLectureReminderResponse =
        timetableLectureReminderService
            .getReminder(userId, timetableId, timetableLectureId)
            .toResponse()

    @GetMapping("/reminders")
    fun getReminders(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
    ): List<TimetableLectureReminderResponse> = timetableLectureReminderService.getReminders(userId, timetableId).map { it.toResponse() }

    @PutMapping("/{timetableLectureId}/reminder")
    fun modifyReminder(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestBody body: TimetableLectureReminderModifyRequest,
    ): TimetableLectureReminderResponse =
        timetableLectureReminderService
            .modifyReminder(userId, timetableId, timetableLectureId, body.option)
            .toResponse()
}

private fun TimetableLectureReminderDisplay.toResponse() =
    TimetableLectureReminderResponse(
        timetableLectureId = timetableLectureId,
        courseTitle = courseTitle,
        option = option,
    )
