package com.wafflestudio.snutt.api.v2.timetable

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.timetable.service.CustomTimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureModifyRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TimetableLectureAddRequestBody(
    val lectureId: Long,
    val isForced: Boolean = false,
)

data class CustomTimetableLectureAddRequestBody(
    @field:NotBlank val courseTitle: String,
    val instructor: String? = null,
    val credit: Int? = null,
    val classPlaceAndTimes: List<ClassPlaceAndTimeRequestBody> = emptyList(),
    val remark: String? = null,
    val color: ColorSet? = null,
    val colorIndex: Int? = null,
    val isForced: Boolean = false,
)

data class TimetableLectureModifyRequestBody(
    val courseTitle: String? = null,
    val instructor: String? = null,
    val credit: Int? = null,
    val classPlaceAndTimes: List<ClassPlaceAndTimeRequestBody>? = null,
    val remark: String? = null,
    val color: ColorSet? = null,
    val colorIndex: Int? = null,
    val academicYear: String? = null,
    val category: String? = null,
    val classification: String? = null,
    val categoryPre2025: String? = null,
    val isForced: Boolean = false,
)

data class ResetLectureRequestBody(
    val isForced: Boolean = false,
)

data class ClassPlaceAndTimeRequestBody(
    val day: Int,
    val place: String? = null,
    val startMinute: Int,
    val endMinute: Int,
)

private fun parseClassPlaceAndTime(body: ClassPlaceAndTimeRequestBody): ClassPlaceAndTime {
    val day =
        DayOfWeek.getOfValue(body.day) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
    return ClassPlaceAndTime(day = day, place = body.place.orEmpty(), startMinute = body.startMinute, endMinute = body.endMinute)
}

@RestController
@RequestMapping("/v2/timetables/{timetableId}/lectures")
class TimetableLectureController(
    private val timetableLectureService: TimetableLectureService,
) {
    @PostMapping("")
    fun addLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: Long,
        @RequestBody body: TimetableLectureAddRequestBody,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .addLecture(
                user.id!!,
                timetableId,
                TimetableLectureAddRequest(lectureId = body.lectureId, isForced = body.isForced),
            ).toResponse(clientInfo.language)

    @PostMapping("/custom")
    fun addCustomLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: Long,
        @Valid @RequestBody body: CustomTimetableLectureAddRequestBody,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .addCustomLecture(
                user.id!!,
                timetableId,
                CustomTimetableLectureAddRequest(
                    courseTitle = body.courseTitle,
                    instructor = body.instructor,
                    credit = body.credit,
                    classPlaceAndTimes = body.classPlaceAndTimes.map { parseClassPlaceAndTime(it) },
                    remark = body.remark,
                    color = body.color,
                    colorIndex = body.colorIndex,
                    isForced = body.isForced,
                ),
            ).toResponse(clientInfo.language)

    @PatchMapping("/{timetableLectureId}")
    fun modifyLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestBody body: TimetableLectureModifyRequestBody,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .modifyLecture(
                user.id!!,
                timetableId,
                timetableLectureId,
                TimetableLectureModifyRequest(
                    courseTitle = body.courseTitle,
                    instructor = body.instructor,
                    credit = body.credit,
                    classPlaceAndTimes = body.classPlaceAndTimes?.map { parseClassPlaceAndTime(it) },
                    remark = body.remark,
                    color = body.color,
                    colorIndex = body.colorIndex,
                    academicYear = body.academicYear,
                    category = body.category,
                    classification = body.classification,
                    categoryPre2025 = body.categoryPre2025,
                    isForced = body.isForced,
                ),
            ).toResponse(clientInfo.language)

    @PostMapping("/{timetableLectureId}/reset")
    fun resetLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestBody(required = false) body: ResetLectureRequestBody?,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .resetLecture(user.id!!, timetableId, timetableLectureId, body?.isForced ?: false)
            .toResponse(clientInfo.language)

    @DeleteMapping("/{timetableLectureId}")
    fun deleteLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableLectureService
            .deleteLecture(user.id!!, timetableId, timetableLectureId)
            .toResponse(clientInfo.language)
}
