package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.LectureOverrideField
import com.wafflestudio.snutt.core.domain.timetable.model.LectureOverrides
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class TimetableLectureAddRequest(
    val lectureId: Long,
    val isForced: Boolean = false,
)

data class CustomTimetableLectureAddRequest(
    val courseTitle: String,
    val instructor: String? = null,
    val credit: Int? = null,
    val classPlaceAndTimes: List<ClassPlaceAndTime> = emptyList(),
    val remark: String? = null,
    val customColor: ColorSet? = null,
    val paletteIndex: Int? = null,
    val isForced: Boolean = false,
)

data class TimetableLectureModifyRequest(
    val resetFields: Set<LectureOverrideField> = emptySet(),
    val courseTitle: String? = null,
    val instructor: String? = null,
    val credit: Int? = null,
    val classPlaceAndTimes: List<ClassPlaceAndTime>? = null,
    val remark: String? = null,
    val customColor: ColorSet? = null,
    val paletteIndex: Int? = null,
    val academicYear: String? = null,
    val category: String? = null,
    val classification: String? = null,
    val categoryPre2025: String? = null,
    val isForced: Boolean = false,
)

@Service
class TimetableLectureService(
    private val timetableService: TimetableService,
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: LectureRepository,
    private val lectureService: LectureService,
    private val timetableLectureReminderService: TimetableLectureReminderService,
) {
    @Transactional
    fun addLecture(
        userId: Long,
        timetableId: Long,
        request: TimetableLectureAddRequest,
    ): TimetableDisplay {
        val timetable = lockTimetable(userId, timetableId)
        val lecture =
            lectureRepository.findByIdOrNull(request.lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        if (timetable.year != lecture.year || timetable.semester != lecture.semester) {
            throw SnuttException(ErrorType.WRONG_SEMESTER)
        }
        val display = timetableService.displayOf(timetable)
        if (display.lectures.any { it.lectureId == lecture.id }) throw SnuttException(ErrorType.DUPLICATE_LECTURE)

        val classTimes = lectureService.classTimesByLectureId(listOf(lecture.id!!))[lecture.id!!].orEmpty()
        val remaining = resolveTimeConflict(display, classTimes, request.isForced, null)

        val paletteIndex = newPaletteIndex(display.theme, remaining)
        timetableLectureRepository.save(
            TimetableLecture(timetableId = timetable.id!!, lectureId = lecture.id, paletteIndex = paletteIndex),
        )
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun addCustomLecture(
        userId: Long,
        timetableId: Long,
        request: CustomTimetableLectureAddRequest,
    ): TimetableDisplay {
        val timetable = lockTimetable(userId, timetableId)
        if (request.courseTitle.isBlank()) throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
        if (request.customColor != null && request.paletteIndex != null) throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
        validateClassTimes(request.classPlaceAndTimes)

        val display = timetableService.displayOf(timetable)
        val remaining = resolveTimeConflict(display, request.classPlaceAndTimes, request.isForced, null)

        val paletteIndex =
            request.paletteIndex?.also { validatePaletteIndex(display.theme, it) } ?: newPaletteIndex(display.theme, remaining)
        timetableLectureRepository.save(
            TimetableLecture(
                timetableId = timetable.id!!,
                lectureId = null,
                customColor = request.customColor,
                paletteIndex = paletteIndex,
                overrides =
                    LectureOverrides(
                        courseTitle = request.courseTitle,
                        instructor = request.instructor,
                        credit = request.credit,
                        remark = request.remark,
                        classPlaceAndTimes = request.classPlaceAndTimes,
                    ),
            ),
        )
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun modifyLecture(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        request: TimetableLectureModifyRequest,
    ): TimetableDisplay {
        val timetable = lockTimetable(userId, timetableId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureId)
        if (request.customColor != null && request.paletteIndex != null) throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
        if (request.courseTitle?.isBlank() == true) throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
        val display = timetableService.displayOf(timetable)
        val lecture =
            timetableLecture.lectureId?.let {
                lectureRepository.findByIdOrNull(it) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
            }
        val lectureTimes = lecture?.let { lectureService.classTimesByLectureId(listOf(it.id!!))[it.id!!] }.orEmpty()

        val timesReset = LectureOverrideField.CLASS_PLACE_AND_TIMES in request.resetFields
        val timesChanged = request.classPlaceAndTimes != null || timesReset
        val newTimes =
            request.classPlaceAndTimes
                ?: if (timesReset) {
                    lectureTimes
                } else {
                    display.lectures.first { it.id == timetableLecture.id }.classPlaceAndTimes
                }
        validateClassTimes(newTimes)
        if (timesChanged) resolveTimeConflict(display, newTimes, request.isForced, timetableLecture.id)

        request.paletteIndex?.let {
            validatePaletteIndex(display.theme, it)
            timetableLecture.paletteIndex = it
            timetableLecture.customColor = null
        }
        request.customColor?.let { timetableLecture.customColor = it }

        timetableLecture.updateOverrides { previous ->
            val o = previous.without(request.resetFields)
            val merged =
                o.copy(
                    courseTitle = request.courseTitle ?: o.courseTitle,
                    instructor = request.instructor ?: o.instructor,
                    credit = request.credit ?: o.credit,
                    remark = request.remark ?: o.remark,
                    classPlaceAndTimes = request.classPlaceAndTimes ?: o.classPlaceAndTimes,
                    academicYear = request.academicYear ?: o.academicYear,
                    category = request.category ?: o.category,
                    classification = request.classification ?: o.classification,
                    categoryPre2025 = request.categoryPre2025 ?: o.categoryPre2025,
                )
            lecture?.let { merged.withoutValuesOf(it, lectureTimes) } ?: merged
        }

        if (timetableLecture.lectureId == null && timetableLecture.overrides?.courseTitle.isNullOrBlank()) {
            throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
        }
        if (timesChanged) timetableLectureReminderService.recomputeForTimetableLecture(timetableLecture.id!!, newTimes)
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun resetLecture(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        isForced: Boolean,
    ): TimetableDisplay {
        val timetable = lockTimetable(userId, timetableId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureId)
        val lectureId = timetableLecture.lectureId ?: throw SnuttException(ErrorType.CANNOT_RESET_CUSTOM_LECTURE)

        val classTimes = lectureService.classTimesByLectureId(listOf(lectureId))[lectureId].orEmpty()
        resolveTimeConflict(timetableService.displayOf(timetable), classTimes, isForced, timetableLecture.id)

        timetableLecture.clearOverrides()
        timetableLectureReminderService.recomputeForTimetableLecture(timetableLecture.id!!, classTimes)
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun deleteLecture(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
    ): TimetableDisplay {
        val timetable = lockTimetable(userId, timetableId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureId)
        timetableLectureRepository.delete(timetableLecture)
        return displayAfterLectureChange(userId, timetable)
    }

    private fun displayAfterLectureChange(
        userId: Long,
        timetable: Timetable,
    ): TimetableDisplay {
        timetableRepository.touchUpdatedAt(listOf(timetable.id!!), Instant.now())
        return timetableService.getTimetableDisplay(userId, timetable.id!!)
    }

    private fun lockTimetable(
        userId: Long,
        timetableId: Long,
    ): Timetable =
        timetableRepository.findForUpdateByIdAndUserId(timetableId, userId)
            ?: throw SnuttException(ErrorType.TIMETABLE_NOT_FOUND)

    private fun getTimetableLecture(
        timetable: Timetable,
        timetableLectureId: Long,
    ): TimetableLecture =
        timetableLectureRepository.findByIdAndTimetableId(timetableLectureId, timetable.id!!)
            ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)

    private fun newPaletteIndex(
        theme: TimetableThemeDisplay,
        lectures: List<TimetableLectureDisplay>,
    ): Int {
        val counts = theme.colors.indices.associateWith { index -> lectures.count { it.paletteIndex == index } }
        val least = counts.values.min()
        return counts.filterValues { it == least }.keys.random()
    }

    private fun validatePaletteIndex(
        theme: TimetableThemeDisplay,
        index: Int,
    ) {
        if (index !in theme.colors.indices) throw SnuttException(ErrorType.INVALID_BODY_FIELD_VALUE)
    }

    private fun validateClassTimes(times: List<ClassPlaceAndTime>) {
        val hasInvalidRange =
            times.any { time ->
                time.startMinute !in 0 until MINUTES_PER_DAY ||
                    time.endMinute !in 1..MINUTES_PER_DAY ||
                    time.startMinute >= time.endMinute
            }
        if (hasInvalidRange || ClassTimeUtils.timesOverlap(times)) throw SnuttException(ErrorType.INVALID_TIME)
    }

    private fun resolveTimeConflict(
        display: TimetableDisplay,
        newTimes: List<ClassPlaceAndTime>,
        isForced: Boolean,
        selfId: Long?,
    ): List<TimetableLectureDisplay> {
        val (overlapping, remaining) =
            display.lectures.partition { it.id != selfId && ClassTimeUtils.timesOverlap(newTimes, it.classPlaceAndTimes) }
        if (overlapping.isEmpty()) return remaining
        if (!isForced) {
            throw SnuttException(ErrorType.LECTURE_TIME_OVERLAP, displayMessage = makeOverwritingConfirmMessage(overlapping))
        }
        timetableLectureRepository.deleteAllById(overlapping.map { it.id })
        return remaining
    }

    private fun makeOverwritingConfirmMessage(overlappingLectures: List<TimetableLectureDisplay>): String {
        val overlappingLectureTitles = overlappingLectures.map { "'${it.courseTitle}'" }.take(2).joinToString(", ")
        val shortFormOfTitles = if (overlappingLectures.size < 3) "" else "외 ${overlappingLectures.size - 2}개의 "
        return "$overlappingLectureTitles ${shortFormOfTitles}강의와 시간이 겹칩니다. 강의를 덮어씌우겠습니까?"
    }

    companion object {
        private const val MINUTES_PER_DAY = 24 * 60
    }
}
