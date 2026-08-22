package com.wafflestudio.snutt.v1compat.snutt

import com.fasterxml.jackson.annotation.JsonProperty
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.DayOfWeek
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.service.CustomTimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureModifyRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
import com.wafflestudio.snutt.v1compat.auth.V1ApiKeyInterceptor
import com.wafflestudio.snutt.v1compat.auth.V1CurrentUser
import com.wafflestudio.snutt.v1compat.snutt.dto.LegacyTimetableDto
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class LegacyTimetableBriefDto(
    @param:JsonProperty("_id")
    val id: String,
    val year: Int,
    val semester: Semester,
    val title: String,
    val isPrimary: Boolean,
    @param:JsonProperty("updated_at")
    val updatedAt: Long,
    @param:JsonProperty("total_credit")
    val totalCredit: Int,
)

data class LegacyTimetableAddRequest(
    val year: Int,
    val semester: Int,
    val title: String,
)

data class LegacyTimetableModifyRequest(
    val title: String,
)

data class LegacyTimetableModifyThemeRequest(
    val theme: Int? = null,
    val themeId: Long? = null,
)

@RestController
@RequestMapping("/v1/tables")
class V1CompatTimetableController(
    private val timetableService: TimetableService,
    private val timetableLectureService: TimetableLectureService,
    private val timetableThemeService: TimetableThemeService,
    private val lectureService: LectureService,
    private val evaluationService: EvaluationService,
) {
    @GetMapping("")
    fun getTimetableBriefs(
        @V1CurrentUser user: User,
    ): List<LegacyTimetableBriefDto> {
        val userId = user.id!!
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { brief ->
            LegacyTimetableBriefDto(
                id = brief.id!!.toString(),
                year = brief.year,
                semester = brief.semester,
                title = brief.title,
                isPrimary = brief.isPrimary,
                updatedAt = brief.updatedAt.toEpochMilli(),
                totalCredit = brief.totalCredit,
            )
        }
    }

    @GetMapping("/recent")
    fun getMostRecentlyUpdatedTimetable(
        @V1CurrentUser user: User,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTimetableDto {
        val timetable = timetableService.getMostRecentlyUpdatedTimetable(user.id!!)
        return toLegacy(user, timetable, clientInfo.language)
    }

    @GetMapping("/{year}/{semester}")
    fun getTimetablesBySemester(
        @V1CurrentUser user: User,
        @PathVariable year: Int,
        @PathVariable semester: Int,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): List<LegacyTimetableDto> =
        timetableService
            .getTimetablesBySemester(user.id!!, year, parseSemester(semester))
            .map { toLegacy(user, it, clientInfo.language) }

    @PostMapping("")
    fun addTimetable(
        @V1CurrentUser user: User,
        @RequestParam(required = false) source: Long?,
        @RequestBody body: LegacyTimetableAddRequest,
    ): List<LegacyTimetableBriefDto> {
        val userId = user.id!!
        if (source == null) {
            timetableService.addTimetable(userId, body.year, parseSemester(body.semester), body.title)
        } else {
            timetableService.copyTimetable(userId, source)
        }
        return getTimetableBriefs(user)
    }

    @GetMapping("/{timetableId}")
    fun getTimetable(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTimetableDto = toLegacy(user, timetableService.getTimetable(user.id!!, timetableId), clientInfo.language)

    @RequestMapping(
        value = ["/{timetableId}"],
        method = [RequestMethod.PUT, RequestMethod.PATCH],
    )
    fun modifyTimetable(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @RequestBody body: LegacyTimetableModifyRequest,
    ): List<LegacyTimetableBriefDto> {
        timetableService.modifyTimetableTitle(user.id!!, timetableId, body.title)
        return getTimetableBriefs(user)
    }

    @DeleteMapping("/{timetableId}")
    fun deleteTimetable(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
    ): List<LegacyTimetableBriefDto> {
        timetableService.deleteTimetable(user.id!!, timetableId)
        return getTimetableBriefs(user)
    }

    @PostMapping("/{timetableId}/copy")
    fun copyTimetable(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
    ): List<LegacyTimetableBriefDto> {
        timetableService.copyTimetable(user.id!!, timetableId)
        return getTimetableBriefs(user)
    }

    @PutMapping("/{timetableId}/theme")
    fun modifyTimetableTheme(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @RequestBody body: LegacyTimetableModifyThemeRequest,
    ): LegacyTimetableDto {
        if ((body.themeId == null) == (body.theme == null)) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val themeId =
            body.themeId
                ?: timetableThemeService
                    .findThemeById(
                        timetableThemeService.builtinThemeId(BasicThemeType.fromValue(body.theme!!)),
                    ).id!!
        val display = timetableService.modifyTimetableTheme(user.id!!, timetableId, themeId)
        return LegacyTimetableDto(
            timetable = display.timetable,
            userId = user.id!!.toString(),
            display = display,
            evLectureIds = emptyMap(),
        )
    }

    @PostMapping("/{timetableId}/primary")
    fun setPrimary(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
    ) {
        timetableService.setPrimary(user.id!!, timetableId)
    }

    @DeleteMapping("/{timetableId}/primary")
    fun unsetPrimary(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
    ) {
        timetableService.unsetPrimary(user.id!!, timetableId)
    }

    @PostMapping("/{timetableId}/lecture")
    fun addCustomLecture(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody body: LegacyCustomLectureRequest,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display =
            timetableLectureService.addCustomLecture(
                user.id!!,
                timetable.id!!,
                CustomTimetableLectureAddRequest(
                    courseTitle = body.courseTitle,
                    instructor = body.instructor,
                    credit = body.credit,
                    classPlaceAndTimes = body.classPlaceAndTimes?.map { it.toClassPlaceAndTime() }.orEmpty(),
                    remark = body.remark,
                    color = body.color?.toColorSet(),
                    colorIndex = body.colorIndex,
                    isForced = isForced ?: body.isForced ?: false,
                ),
            )
        return toLegacy(user, timetable, display, clientInfo.language)
    }

    @PostMapping("/{timetableId}/lecture/{lectureId}")
    fun addLecture(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable lectureId: Long,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody(required = false) body: LegacyForcedRequest?,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display =
            timetableLectureService.addLecture(
                user.id!!,
                timetable.id!!,
                TimetableLectureAddRequest(
                    lectureId = lectureId,
                    isForced =
                        isForced ?: body?.isForced ?: false,
                ),
            )
        return toLegacy(user, timetable, display, clientInfo.language)
    }

    @PutMapping("/{timetableId}/lecture/{timetableLectureId}/reset")
    fun resetTimetableLecture(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody(required = false) body: LegacyForcedRequest?,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display =
            timetableLectureService.resetLecture(
                user.id!!,
                timetableId,
                timetableLectureId,
                isForced ?: body?.isForced ?: false,
            )
        return toLegacy(user, timetable, display, clientInfo.language)
    }

    @PutMapping("/{timetableId}/lecture/{timetableLectureId}")
    fun modifyTimetableLecture(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody body: LegacyModifyLectureRequest,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display =
            timetableLectureService.modifyLecture(
                user.id!!,
                timetableId,
                timetableLectureId,
                TimetableLectureModifyRequest(
                    courseTitle = body.courseTitle,
                    instructor = body.instructor,
                    credit = body.credit,
                    classPlaceAndTimes = body.classPlaceAndTimes?.map { it.toClassPlaceAndTime() },
                    remark = body.remark,
                    color = body.color?.toColorSet(),
                    colorIndex = body.colorIndex,
                    isForced = isForced ?: body.isForced ?: false,
                ),
            )
        return toLegacy(user, timetable, display, clientInfo.language)
    }

    @DeleteMapping("/{timetableId}/lecture/{timetableLectureId}")
    fun deleteTimetableLecture(
        @V1CurrentUser user: User,
        @PathVariable timetableId: Long,
        @PathVariable timetableLectureId: Long,
        @RequestAttribute(V1ApiKeyInterceptor.CLIENT_INFO_ATTRIBUTE) clientInfo: ClientInfo,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display = timetableLectureService.deleteLecture(user.id!!, timetableId, timetableLectureId)
        return toLegacy(user, timetable, display, clientInfo.language)
    }

    private fun toLegacy(
        user: User,
        timetable: Timetable,
        language: Language = Language.KO,
    ): LegacyTimetableDto {
        val display = timetableService.getTimetableDisplay(user.id!!, timetable.id!!)
        return toLegacy(user, timetable, display, language)
    }

    private fun toLegacy(
        user: User,
        timetable: Timetable,
        display: TimetableDisplay,
        language: Language = Language.KO,
    ): LegacyTimetableDto {
        val evLectureIds = fetchEvLectureIds(display.lectures.mapNotNull { it.lectureId })
        return LegacyTimetableDto(
            timetable = timetable,
            userId = user.id!!.toString(),
            display = display,
            evLectureIds = evLectureIds,
            language = language,
        )
    }

    private fun fetchEvLectureIds(lectureIds: List<Long>): Map<String, Long> {
        if (lectureIds.isEmpty()) return emptyMap()
        val summaries = evaluationService.findSummariesByLectureIds(lectureIds)
        return lectureIds
            .filter { it in summaries }
            .associate { it.toString() to it }
    }

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}

data class LegacyForcedRequest(
    @param:JsonProperty("is_forced")
    val isForced: Boolean? = null,
)

data class LegacyColorRequest(
    val bg: String? = null,
    val fg: String? = null,
)

fun LegacyColorRequest.toColorSet() = ColorSet(backgroundColor = bg, foregroundColor = fg)

data class LegacyClassTimeRequest(
    val day: Int,
    val place: String? = null,
    @param:JsonProperty("start_minute")
    val startMinute: Int,
    @param:JsonProperty("end_minute")
    val endMinute: Int,
)

fun LegacyClassTimeRequest.toClassPlaceAndTime() =
    ClassPlaceAndTime(
        day = DayOfWeek.getOfValue(day) ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
        place = place.orEmpty(),
        startMinute = startMinute,
        endMinute = endMinute,
    )

data class LegacyCustomLectureRequest(
    @param:JsonProperty("course_title")
    val courseTitle: String,
    val instructor: String? = null,
    val credit: Int? = null,
    @param:JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassTimeRequest>? = null,
    val remark: String? = null,
    val color: LegacyColorRequest? = null,
    @param:JsonProperty("color_index")
    val colorIndex: Int? = null,
    @param:JsonProperty("is_forced")
    val isForced: Boolean? = null,
)

data class LegacyModifyLectureRequest(
    @param:JsonProperty("course_title")
    val courseTitle: String? = null,
    val instructor: String? = null,
    val credit: Int? = null,
    @param:JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassTimeRequest>? = null,
    val remark: String? = null,
    val color: LegacyColorRequest? = null,
    @param:JsonProperty("color_index")
    val colorIndex: Int? = null,
    @param:JsonProperty("is_forced")
    val isForced: Boolean? = null,
)
