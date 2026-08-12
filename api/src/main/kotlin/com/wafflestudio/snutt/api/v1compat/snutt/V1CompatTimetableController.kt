package com.wafflestudio.snutt.api.v1compat.snutt

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyEvSummary
import com.wafflestudio.snutt.api.v1compat.snutt.dto.LegacyTimetableDto
import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.evaluation.service.EvaluationService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.service.CustomTimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureAddRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureModifyRequest
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableLectureService
import com.wafflestudio.snutt.core.domain.timetable.service.TimetableService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// v1 시간표 응답: {_id, user_id, year, semester, title, theme, themeId, isPrimary, updated_at} (brief)
data class LegacyTimetableBriefDto(
    @com.fasterxml.jackson.annotation.JsonProperty("_id")
    val id: String,
    val year: Int,
    val semester: Semester,
    val title: String,
    val isPrimary: Boolean,
    @com.fasterxml.jackson.annotation.JsonProperty("updated_at")
    val updatedAt: Long,
    @com.fasterxml.jackson.annotation.JsonProperty("total_credit")
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
    val themeId: String? = null,
)

@RestController
@RequestMapping("/v1/tables", "/tables")
class V1CompatTimetableController(
    private val timetableService: TimetableService,
    private val timetableLectureService: TimetableLectureService,
    private val lectureRepository: LectureRepository,
    private val evaluationService: EvaluationService,
) {
    @GetMapping("")
    fun getTimetableBriefs(
        @CurrentUser user: User,
    ): List<LegacyTimetableBriefDto> {
        val userId = user.id!!
        return timetableService.toBriefs(timetableService.getTimetables(userId)).map { brief ->
            LegacyTimetableBriefDto(
                id = brief.id,
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
        @CurrentUser user: User,
    ): LegacyTimetableDto {
        val timetable = timetableService.getMostRecentlyUpdatedTimetable(user.id!!)
        return toLegacy(user, timetable)
    }

    @GetMapping("/{year}/{semester}")
    fun getTimetablesBySemester(
        @CurrentUser user: User,
        @PathVariable year: Int,
        @PathVariable semester: Int,
    ): List<LegacyTimetableDto> =
        timetableService
            .getTimetablesBySemester(user.id!!, year, parseSemester(semester))
            .map { toLegacy(user, it) }

    @PostMapping("")
    fun addTimetable(
        @CurrentUser user: User,
        @RequestParam(required = false) source: String?,
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
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ): LegacyTimetableDto = toLegacy(user, timetableService.getTimetable(user.id!!, timetableId))

    // v1은 이름 변경에 PUT과 PATCH를 모두 받는다
    @RequestMapping(
        value = ["/{timetableId}"],
        method = [RequestMethod.PUT, RequestMethod.PATCH],
    )
    fun modifyTimetable(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @RequestBody body: LegacyTimetableModifyRequest,
    ): List<LegacyTimetableBriefDto> {
        timetableService.modifyTimetableTitle(user.id!!, timetableId, body.title)
        return getTimetableBriefs(user)
    }

    @DeleteMapping("/{timetableId}")
    fun deleteTimetable(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ): List<LegacyTimetableBriefDto> {
        timetableService.deleteTimetable(user.id!!, timetableId)
        return getTimetableBriefs(user)
    }

    @PostMapping("/{timetableId}/copy")
    fun copyTimetable(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
    ): List<LegacyTimetableBriefDto> {
        timetableService.copyTimetable(user.id!!, timetableId)
        return getTimetableBriefs(user)
    }

    @PutMapping("/{timetableId}/theme")
    fun modifyTimetableTheme(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @RequestBody body: LegacyTimetableModifyThemeRequest,
    ): LegacyTimetableDto {
        if ((body.themeId == null) == (body.theme == null)) throw SnuttException(ErrorType.INVALID_PARAMETER)
        val theme = body.theme?.let { BasicThemeType.fromValue(it) }
        val display = timetableService.modifyTimetableTheme(user.id!!, timetableId, theme, body.themeId)
        return LegacyTimetableDto(
            timetable = display.timetable,
            userId = user.externalId,
            display = display,
            evSummaries = emptyMap(),
        )
    }

    @PostMapping("/{timetableId}/primary")
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

    // v1 TimetableLectureController (단수 lecture 경로) — 응답은 전체 레거시 시간표
    @PostMapping("/{timetableId}/lecture")
    fun addCustomLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody body: LegacyCustomLectureRequest,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display =
            timetableLectureService.addCustomLecture(
                user.id!!,
                timetableId,
                CustomTimetableLectureAddRequest(
                    courseTitle = body.courseTitle,
                    instructor = body.instructor,
                    credit = body.credit,
                    classPlaceAndTime = body.classPlaceAndTimes?.map { it.toClassPlaceAndTime() }.orEmpty(),
                    remark = body.remark,
                    color =
                        body.color?.let {
                            com.wafflestudio.snutt.core.domain.theme.model.ColorSet(
                                backgroundColor = it.bg,
                                foregroundColor = it.fg,
                            )
                        },
                    colorIndex = body.colorIndex,
                    isForced = isForced ?: body.isForced ?: false,
                ),
            )
        return toLegacy(user, timetable, display)
    }

    @PostMapping("/{timetableId}/lecture/{lectureId}")
    fun addLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable lectureId: String,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody(required = false) body: LegacyForcedRequest?,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display =
            timetableLectureService.addLecture(
                user.id!!,
                timetableId,
                TimetableLectureAddRequest(lectureId = lectureId, isForced = isForced ?: body?.isForced ?: false),
            )
        return toLegacy(user, timetable, display)
    }

    @PutMapping("/{timetableId}/lecture/{timetableLectureId}/reset")
    fun resetTimetableLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody(required = false) body: LegacyForcedRequest?,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display =
            timetableLectureService.resetLecture(
                user.id!!,
                timetableId,
                timetableLectureId,
                isForced ?: body?.isForced ?: false,
            )
        return toLegacy(user, timetable, display)
    }

    @PutMapping("/{timetableId}/lecture/{timetableLectureId}")
    fun modifyTimetableLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
        @RequestParam(required = false) isForced: Boolean?,
        @RequestBody body: LegacyModifyLectureRequest,
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
                    classPlaceAndTime = body.classPlaceAndTimes?.map { it.toClassPlaceAndTime() },
                    remark = body.remark,
                    color =
                        body.color?.let {
                            com.wafflestudio.snutt.core.domain.theme.model.ColorSet(
                                backgroundColor = it.bg,
                                foregroundColor = it.fg,
                            )
                        },
                    colorIndex = body.colorIndex,
                    isForced = isForced ?: body.isForced ?: false,
                ),
            )
        return toLegacy(user, timetable, display)
    }

    @DeleteMapping("/{timetableId}/lecture/{timetableLectureId}")
    fun deleteTimetableLecture(
        @CurrentUser user: User,
        @PathVariable timetableId: String,
        @PathVariable timetableLectureId: String,
    ): LegacyTimetableDto {
        val timetable = timetableService.getTimetable(user.id!!, timetableId)
        val display = timetableLectureService.deleteLecture(user.id!!, timetableId, timetableLectureId)
        return toLegacy(user, timetable, display)
    }

    private fun toLegacy(
        user: User,
        timetable: Timetable,
    ): LegacyTimetableDto {
        val display = timetableService.getTimetableDisplay(user.id!!, timetable.externalId)
        return toLegacy(user, timetable, display)
    }

    private fun toLegacy(
        user: User,
        timetable: Timetable,
        display: com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay,
    ): LegacyTimetableDto {
        val evSummaries = fetchEvSummaries(display.lectures.mapNotNull { it.lectureId })
        return LegacyTimetableDto(
            timetable = timetable,
            userId = user.externalId,
            display = display,
            evSummaries = evSummaries,
        )
    }

    // lecture 공개 id(hex) → course 집계 요약 (evLectureId는 재채번된 course id)
    private fun fetchEvSummaries(lectureExternalIds: List<String>): Map<String, LegacyEvSummary> {
        if (lectureExternalIds.isEmpty()) return emptyMap()
        val numericIds = lectureRepository.findAllByExternalIdIn(lectureExternalIds).associate { it.externalId to it.id!! }
        val summaries = evaluationService.findSummariesByLectureIds(numericIds.values)
        return numericIds
            .mapValues { (_, numericId) ->
                summaries[numericId]?.let {
                    LegacyEvSummary(evLectureId = numericId, avgRating = it.avgRating, evaluationCount = it.evalCount)
                }
            }.filterValues { it != null }
            .mapValues { it.value!! }
    }

    private fun parseSemester(value: Int): Semester = Semester.getOfValue(value) ?: throw SnuttException(ErrorType.INVALID_PARAMETER)
}

// v1 강의 추가/수정 요청 (snake_case)
data class LegacyForcedRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("is_forced")
    val isForced: Boolean? = null,
)

data class LegacyColorRequest(
    val bg: String? = null,
    val fg: String? = null,
)

data class LegacyClassTimeRequest(
    val day: Int,
    val place: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("start_minute")
    val startMinute: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("end_minute")
    val endMinute: Int,
)

fun LegacyClassTimeRequest.toClassPlaceAndTime() =
    com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime(
        day =
            com.wafflestudio.snutt.core.common.enums.DayOfWeek
                .getOfValue(day)
                ?: throw SnuttException(ErrorType.INVALID_PARAMETER),
        place = place.orEmpty(),
        startMinute = startMinute,
        endMinute = endMinute,
    )

data class LegacyCustomLectureRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("course_title")
    val courseTitle: String,
    val instructor: String? = null,
    val credit: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassTimeRequest>? = null,
    val remark: String? = null,
    val color: LegacyColorRequest? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("color_index")
    val colorIndex: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("is_forced")
    val isForced: Boolean? = null,
)

data class LegacyModifyLectureRequest(
    @com.fasterxml.jackson.annotation.JsonProperty("course_title")
    val courseTitle: String? = null,
    val instructor: String? = null,
    val credit: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("class_time_json")
    val classPlaceAndTimes: List<LegacyClassTimeRequest>? = null,
    val remark: String? = null,
    val color: LegacyColorRequest? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("color_index")
    val colorIndex: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("is_forced")
    val isForced: Boolean? = null,
)
