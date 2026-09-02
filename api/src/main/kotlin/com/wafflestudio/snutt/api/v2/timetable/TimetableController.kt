package com.wafflestudio.snutt.api.v2.timetable

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableBriefDto
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class TimetableAddRequest(
    val year: Int,
    val semester: Int,
    @field:NotBlank val title: String,
)

data class TimetableModifyRequest(
    @field:NotBlank val title: String,
)

data class TimetableModifyThemeRequest(
    val themeId: Long,
)

data class TimetableBriefResponse(
    val id: Long,
    val year: Int,
    val semester: Semester,
    val title: String,
    val isPrimary: Boolean,
    val updatedAt: Long,
    val totalCredit: Int,
)

data class TimetableResponse(
    val id: Long,
    val year: Int,
    val semester: Semester,
    val title: String,
    val themeId: Long,
    val isPrimary: Boolean,
    val updatedAt: Long,
    val lectures: List<TimetableLectureResponse>,
)

data class TimetableLectureResponse(
    val id: Long,
    val lectureId: Long?,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val courseNumber: String?,
    val lectureNumber: String?,
    val department: String?,
    val quota: Int?,
    val freshmanQuota: Int?,
    val courseTitle: String,
    val instructor: String?,
    val credit: Int?,
    val remark: String?,
    val classPlaceAndTimes: List<ClassPlaceAndTimeResponse>,
    val color: ColorSet?,
    val colorIndex: Int,
)

data class ClassPlaceAndTimeResponse(
    val day: Int,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
)

private fun TimetableBriefDto.toResponse() =
    TimetableBriefResponse(
        id = id,
        year = year,
        semester = semester,
        title = title,
        isPrimary = isPrimary,
        updatedAt = updatedAt.toEpochMilli(),
        totalCredit = totalCredit,
    )

internal fun TimetableDisplay.toResponse(language: Language = Language.KO) =
    TimetableResponse(
        id = timetable.id!!,
        year = timetable.year,
        semester = timetable.semester,
        title = timetable.title,
        themeId = timetable.themeId,
        isPrimary = timetable.isPrimary,
        updatedAt = checkNotNull(timetable.updatedAt).toEpochMilli(),
        lectures = lectures.map { it.toResponse(language) },
    )

internal fun TimetableLectureDisplay.toResponse(language: Language = Language.KO) =
    TimetableLectureResponse(
        id = id,
        lectureId = lectureId,
        academicYear = language.select(academicYear, academicYearEn),
        category = language.select(category, categoryEn),
        categoryPre2025 = categoryPre2025,
        classification = language.select(classification, classificationEn),
        courseNumber = courseNumber,
        lectureNumber = lectureNumber,
        department = language.select(department, departmentEn),
        quota = quota,
        freshmanQuota = freshmanQuota,
        courseTitle = language.select(courseTitle, courseTitleEn),
        instructor = language.select(instructor, instructorEn),
        credit = credit,
        remark = language.select(remark, remarkEn),
        classPlaceAndTimes = classPlaceAndTimes.map { it.toResponse() },
        color = color,
        colorIndex = colorIndex,
    )

internal fun ClassPlaceAndTime.toResponse() =
    ClassPlaceAndTimeResponse(day = day.value, place = place, startMinute = startMinute, endMinute = endMinute)

@RestController
@RequestMapping("/v2/timetables")
class TimetableController(
    private val timetableService: TimetableService,
) {
    @GetMapping("")
    fun getTimetableBriefs(
        @CurrentUserId userId: Long,
    ): List<TimetableBriefResponse> = timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }

    @GetMapping("/recent")
    fun getMostRecentlyUpdatedTimetable(
        @CurrentUserId userId: Long,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableService
            .getTimetableDisplay(
                userId,
                timetableService.getMostRecentlyUpdatedTimetable(userId).id!!,
            ).toResponse(clientInfo.language)

    @GetMapping("/{year}/{semester}")
    fun getTimetablesBySemester(
        @CurrentUserId userId: Long,
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute clientInfo: ClientInfo,
    ): List<TimetableResponse> =
        timetableService
            .getTimetablesBySemester(userId, year, parseSemester(semester))
            .map { timetableService.getTimetableDisplay(userId, it.id!!).toResponse(clientInfo.language) }

    @PostMapping("")
    fun addTimetable(
        @CurrentUserId userId: Long,
        @RequestParam(required = false) source: Long?,
        @Valid @RequestBody body: TimetableAddRequest,
    ): List<TimetableBriefResponse> {
        val userId = userId
        if (source == null) {
            timetableService.addTimetable(userId, body.year, parseSemester(body.semester), body.title)
        } else {
            timetableService.copyTimetable(userId, source)
        }
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @GetMapping("/{timetableId}")
    fun getTimetable(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse = timetableService.getTimetableDisplay(userId, timetableId).toResponse(clientInfo.language)

    @PatchMapping("/{timetableId}")
    fun modifyTimetable(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @Valid @RequestBody body: TimetableModifyRequest,
    ): List<TimetableBriefResponse> {
        val userId = userId
        timetableService.modifyTimetableTitle(userId, timetableId, body.title)
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @DeleteMapping("/{timetableId}")
    fun deleteTimetable(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
    ): List<TimetableBriefResponse> {
        val userId = userId
        timetableService.deleteTimetable(userId, timetableId)
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @PostMapping("/{timetableId}/copy")
    fun copyTimetable(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
    ): List<TimetableBriefResponse> {
        val userId = userId
        timetableService.copyTimetable(userId, timetableId)
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @PutMapping("/{timetableId}/theme")
    fun modifyTimetableTheme(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
        @RequestBody body: TimetableModifyThemeRequest,
    ): TimetableResponse = timetableService.modifyTimetableTheme(userId, timetableId, body.themeId).toResponse()

    @PutMapping("/{timetableId}/primary")
    fun setPrimary(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
    ) {
        timetableService.setPrimary(userId, timetableId)
    }

    @DeleteMapping("/{timetableId}/primary")
    fun unsetPrimary(
        @CurrentUserId userId: Long,
        @PathVariable timetableId: Long,
    ) {
        timetableService.unsetPrimary(userId, timetableId)
    }

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
