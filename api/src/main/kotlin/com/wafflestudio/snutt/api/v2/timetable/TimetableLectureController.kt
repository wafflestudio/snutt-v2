package com.wafflestudio.snutt.api.v2.timetable

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.enums.LectureCategoryPre2025
import com.wafflestudio.snutt.core.domain.timetable.service.CustomTimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureModifyRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ResetLectureRequestBody(
    val isForced: Boolean = false,
)

@RestController
@RequestMapping("/v2/timetables/{timetableId}/lectures")
class TimetableLectureController(
    private val timetableLectureService: TimetableLectureService,
) {
    @PostMapping("")
    fun addLecture(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @RequestBody body: TimetableLectureAddRequest,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse = timetableLectureService.addLecture(userId, timetableId, body).toResponse(clientInfo.language)

    @PostMapping("/custom")
    fun addCustomLecture(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @RequestBody body: CustomTimetableLectureAddRequest,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse = timetableLectureService.addCustomLecture(userId, timetableId, body).toResponse(clientInfo.language)

    @PatchMapping("/{timetableLectureId}")
    fun modifyLecture(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestBody body: TimetableLectureModifyRequest,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .modifyLecture(
                userId,
                timetableId,
                timetableLectureId,
                body.copy(categoryPre2025 = body.categoryPre2025?.let { LectureCategoryPre2025.toKorean(it) }),
            ).toResponse(clientInfo.language)

    @PostMapping("/{timetableLectureId}/reset")
    fun resetLecture(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestBody(required = false) body: ResetLectureRequestBody?,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .resetLecture(userId, timetableId, timetableLectureId, body?.isForced ?: false)
            .toResponse(clientInfo.language)

    @DeleteMapping("/{timetableLectureId}")
    fun deleteLecture(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .deleteLecture(userId, timetableId, timetableLectureId)
            .toResponse(clientInfo.language)
}
