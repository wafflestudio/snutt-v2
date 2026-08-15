package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.theme.model.ColorSet
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class TimetableLectureAddRequest(
    val lectureId: String,
    val isForced: Boolean = false,
)

data class CustomTimetableLectureAddRequest(
    val courseTitle: String,
    val instructor: String? = null,
    val credit: Int? = null,
    val classPlaceAndTime: List<ClassPlaceAndTime> = emptyList(),
    val remark: String? = null,
    val color: ColorSet? = null,
    val colorIndex: Int? = null,
    val isForced: Boolean = false,
)

data class TimetableLectureModifyRequest(
    val courseTitle: String? = null,
    val instructor: String? = null,
    val credit: Int? = null,
    val classPlaceAndTime: List<ClassPlaceAndTime>? = null,
    val remark: String? = null,
    val color: ColorSet? = null,
    val colorIndex: Int? = null,
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
    private val timetableThemeService: TimetableThemeService,
    private val timetableLectureReminderService: TimetableLectureReminderService,
) {
    @Transactional
    fun addLecture(
        userId: Long,
        timetableExternalId: String,
        request: TimetableLectureAddRequest,
    ): TimetableDisplay {
        val timetable = timetableService.getTimetable(userId, timetableExternalId)
        val lecture =
            lectureRepository.findByExternalId(request.lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        if (timetable.year != lecture.year || timetable.semester != lecture.semester) {
            throw SnuttException(ErrorType.WRONG_SEMESTER)
        }
        val existingLectures = timetableLectureRepository.findByTimetableId(timetable.id!!)
        if (existingLectures.any { it.lectureId == lecture.id }) throw SnuttException(ErrorType.DUPLICATE_LECTURE)

        val classTimes = lectureService.classTimesByLectureId(listOf(lecture.id!!))[lecture.id!!].orEmpty()
        resolveTimeConflict(timetable, classTimes, request.isForced, null)

        val remaining = timetableLectureRepository.findByTimetableId(timetable.id!!)
        val (colorIndex, color) =
            timetableThemeService.getNewColorIndexAndColor(
                timetable.themeId,
                remaining.map { it.color },
                remaining.map { it.colorIndex },
            )
        timetableLectureRepository.save(
            TimetableLecture(timetableId = timetable.id!!, lectureId = lecture.id, color = color, colorIndex = colorIndex),
        )
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun addCustomLecture(
        userId: Long,
        timetableExternalId: String,
        request: CustomTimetableLectureAddRequest,
    ): TimetableDisplay {
        val timetable = timetableService.getTimetable(userId, timetableExternalId)
        if (ClassTimeUtils.timesOverlap(request.classPlaceAndTime)) throw SnuttException(ErrorType.INVALID_TIME)

        resolveTimeConflict(timetable, request.classPlaceAndTime, request.isForced, null)

        val remaining = timetableLectureRepository.findByTimetableId(timetable.id!!)
        val (colorIndex, color) =
            timetableThemeService.getNewColorIndexAndColor(
                timetable.themeId,
                remaining.map { it.color },
                remaining.map { it.colorIndex },
            )
        timetableLectureRepository.save(
            TimetableLecture(
                timetableId = timetable.id!!,
                lectureId = null,
                color = request.color ?: color,
                colorIndex = request.colorIndex ?: colorIndex,
                courseTitle = request.courseTitle,
                instructor = request.instructor,
                credit = request.credit,
                remark = request.remark,
                classPlaceAndTime = request.classPlaceAndTime,
            ),
        )
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun modifyLecture(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
        request: TimetableLectureModifyRequest,
    ): TimetableDisplay {
        val timetable = timetableService.getTimetable(userId, timetableExternalId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureExternalId)
        val existingDisplays = timetableService.displaysOf(listOf(timetable))[timetable.id!!].orEmpty()

        val newTimes =
            request.classPlaceAndTime
                ?: existingDisplays.first { it.id == timetableLectureExternalId }.classPlaceAndTime
        if (ClassTimeUtils.timesOverlap(newTimes)) throw SnuttException(ErrorType.INVALID_TIME)
        resolveTimeConflict(timetable, newTimes, request.isForced, timetableLectureExternalId)

        request.color?.let { timetableLecture.color = it }
        request.colorIndex?.let { timetableLecture.colorIndex = it }

        request.courseTitle?.let { timetableLecture.courseTitle = it }
        request.instructor?.let { timetableLecture.instructor = it }
        request.credit?.let { timetableLecture.credit = it }
        request.remark?.let { timetableLecture.remark = it }
        request.classPlaceAndTime?.let { timetableLecture.classPlaceAndTime = it }
        request.academicYear?.let { timetableLecture.academicYear = it }
        request.category?.let { timetableLecture.category = it }
        request.classification?.let { timetableLecture.classification = it }
        request.categoryPre2025?.let { timetableLecture.categoryPre2025 = it }

        timetableLectureReminderService.recomputeForTimetableLecture(timetableLecture.id!!, newTimes)
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun resetLecture(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
        isForced: Boolean,
    ): TimetableDisplay {
        val timetable = timetableService.getTimetable(userId, timetableExternalId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureExternalId)
        if (timetableLecture.lectureId == null) throw SnuttException(ErrorType.CANNOT_RESET_CUSTOM_LECTURE)
        val lecture =
            lectureRepository.findByIdOrNull(timetableLecture.lectureId!!) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)

        val classTimes = lectureService.classTimesByLectureId(listOf(lecture.id!!))[lecture.id!!].orEmpty()
        resolveTimeConflict(timetable, classTimes, isForced, timetableLectureExternalId)

        timetableLecture.clearOverrides()
        timetableLectureReminderService.recomputeForTimetableLecture(timetableLecture.id!!, classTimes)
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun deleteLecture(
        userId: Long,
        timetableExternalId: String,
        timetableLectureExternalId: String,
    ): TimetableDisplay {
        val timetable = timetableService.getTimetable(userId, timetableExternalId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureExternalId)
        timetableLectureRepository.delete(timetableLecture)
        return displayAfterLectureChange(userId, timetable)
    }

    private fun displayAfterLectureChange(
        userId: Long,
        timetable: Timetable,
    ): TimetableDisplay {
        timetableRepository.touchUpdatedAt(timetable.id!!)
        return timetableService.getTimetableDisplay(userId, timetable.externalId)
    }

    fun getTimetableLecture(
        timetable: Timetable,
        timetableLectureExternalId: String,
    ): TimetableLecture =
        timetableLectureRepository.findByTimetableIdAndExternalId(timetable.id!!, timetableLectureExternalId)
            ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)

    private fun resolveTimeConflict(
        timetable: Timetable,
        newTimes: List<ClassPlaceAndTime>,
        isForced: Boolean,
        selfExternalId: String?,
    ) {
        val displays = timetableService.displaysOf(listOf(timetable))[timetable.id!!].orEmpty()
        val overlapping =
            displays.filter { display ->
                display.id != selfExternalId && ClassTimeUtils.timesOverlap(newTimes, display.classPlaceAndTime)
            }
        if (overlapping.isEmpty()) return
        if (!isForced) {
            val confirmMessage = makeOverwritingConfirmMessage(overlapping)
            throw SnuttException(ErrorType.LECTURE_TIME_OVERLAP, errorMessage = confirmMessage, displayMessage = confirmMessage)
        }
        val overlappingIds = overlapping.map { it.id }
        timetableLectureRepository.deleteAll(
            timetableLectureRepository.findByTimetableId(timetable.id!!).filter { it.externalId in overlappingIds },
        )
    }

    private fun makeOverwritingConfirmMessage(overlappingLectures: List<TimetableLectureDisplay>): String {
        val overlappingLectureTitles = overlappingLectures.map { "'${it.courseTitle}'" }.take(2).joinToString(", ")
        val shortFormOfTitles = if (overlappingLectures.size < 3) "" else "외 ${overlappingLectures.size - 2}개의 "
        return "$overlappingLectureTitles ${shortFormOfTitles}강의와 시간이 겹칩니다. 강의를 덮어씌우겠습니까?"
    }
}
