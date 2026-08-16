package com.wafflestudio.snutt.api.v2.timetable

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableBriefDto
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
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
    val theme: BasicThemeType? = null,
    val themeId: String? = null,
)

data class TimetableBriefResponse(
    val id: String,
    val year: Int,
    val semester: Semester,
    val title: String,
    val isPrimary: Boolean,
    val updatedAt: Long,
    val totalCredit: Int,
)

data class TimetableResponse(
    val id: String,
    val year: Int,
    val semester: Semester,
    val title: String,
    val theme: BasicThemeType,
    val themeId: String?,
    val isPrimary: Boolean,
    val updatedAt: Long,
    val lectures: List<TimetableLectureResponse>,
)

data class TimetableLectureResponse(
    val id: String,
    val lectureId: String?,
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
        id = timetable.externalId,
        year = timetable.year,
        semester = timetable.semester,
        title = timetable.title,
        theme = timetable.theme,
        themeId = themeExternalId,
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
        @CurrentUser user: User,
    ): List<TimetableBriefResponse> = timetableService.toBriefs(timetableService.getTimetables(user.id!!)).map { it.toResponse() }

    @GetMapping("/recent")
    fun getMostRecentlyUpdatedTimetable(
        @CurrentUser user: User,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse =
        timetableService
            .getTimetableDisplay(
                user.id!!,
                timetableService.getMostRecentlyUpdatedTimetable(user.id!!).externalId,
            ).toResponse(clientInfo.language)

    @GetMapping("/{year}/{semester}")
    fun getTimetablesBySemester(
        @CurrentUser user: User,
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute clientInfo: ClientInfo,
    ): List<TimetableResponse> =
        timetableService
            .getTimetablesBySemester(user.id!!, year, parseSemester(semester))
            .map { timetableService.getTimetableDisplay(user.id!!, it.externalId).toResponse(clientInfo.language) }

    @PostMapping("")
    fun addTimetable(
        @CurrentUser user: User,
        @RequestParam(required = false) source: String?,
        @RequestBody body: TimetableAddRequest,
    ): List<TimetableBriefResponse> {
        val userId = user.id!!
        if (source == null) {
            timetableService.addTimetable(userId, body.year, parseSemester(body.semester), body.title)
        } else {
            timetableService.copyTimetable(userId, source)
        }
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @GetMapping("/{timetableId}")
    fun getTimetable(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @RequestAttribute clientInfo: ClientInfo,
    ): TimetableResponse = timetableService.getTimetableDisplay(user.id!!, timetableId).toResponse(clientInfo.language)

    @PatchMapping("/{timetableId}")
    fun modifyTimetable(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @RequestBody body: TimetableModifyRequest,
    ): List<TimetableBriefResponse> {
        val userId = user.id!!
        timetableService.modifyTimetableTitle(userId, timetableId, body.title)
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @DeleteMapping("/{timetableId}")
    fun deleteTimetable(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ): List<TimetableBriefResponse> {
        val userId = user.id!!
        timetableService.deleteTimetable(userId, timetableId)
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @PostMapping("/{timetableId}/copy")
    fun copyTimetable(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ): List<TimetableBriefResponse> {
        val userId = user.id!!
        timetableService.copyTimetable(userId, timetableId)
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { it.toResponse() }
    }

    @PutMapping("/{timetableId}/theme")
    fun modifyTimetableTheme(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @RequestBody body: TimetableModifyThemeRequest,
    ): TimetableResponse {
        if ((body.themeId == null) == (body.theme == null)) throw SnuttException(ErrorType.INVALID_PARAMETER)
        return timetableService
            .modifyTimetableTheme(user.id!!, timetableId, body.theme, body.themeId)
            .toResponse()
    }

    @PutMapping("/{timetableId}/primary")
    fun setPrimary(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ) {
        timetableService.setPrimary(user.id!!, timetableId)
    }

    @DeleteMapping("/{timetableId}/primary")
    fun unsetPrimary(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ) {
        timetableService.unsetPrimary(user.id!!, timetableId)
    }

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}
