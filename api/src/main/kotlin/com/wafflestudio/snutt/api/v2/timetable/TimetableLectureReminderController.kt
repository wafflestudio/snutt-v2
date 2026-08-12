package com.wafflestudio.snutt.api.v2.timetable

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderOption
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TimetableLectureReminderResponse(
    val timetableLectureId: String,
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
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
    ): TimetableLectureReminderResponse =
        timetableLectureReminderService
            .getReminder(user.id!!, timetableId, timetableLectureId)
            .toResponse()

    @GetMapping("/reminders")
    fun getReminders(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ): List<TimetableLectureReminderResponse> = timetableLectureReminderService.getReminders(user.id!!, timetableId).map { it.toResponse() }

    @PutMapping("/{timetableLectureId}/reminder")
    fun modifyReminder(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
        @RequestBody body: TimetableLectureReminderModifyRequest,
    ): TimetableLectureReminderResponse =
        timetableLectureReminderService
            .modifyReminder(user.id!!, timetableId, timetableLectureId, body.option)
            .toResponse()
}

private fun com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureReminderDisplay.toResponse() =
    TimetableLectureReminderResponse(
        timetableLectureId = timetableLectureId,
        courseTitle = courseTitle,
        option = option,
    )
