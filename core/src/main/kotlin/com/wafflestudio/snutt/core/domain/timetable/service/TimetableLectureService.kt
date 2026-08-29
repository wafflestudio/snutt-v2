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
import com.wafflestudio.snutt.core.domain.timetable.model.LectureOverrides
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    val color: ColorSet? = null,
    val colorIndex: Int? = null,
    val isForced: Boolean = false,
)

data class TimetableLectureModifyRequest(
    val courseTitle: String? = null,
    val instructor: String? = null,
    val credit: Int? = null,
    val classPlaceAndTimes: List<ClassPlaceAndTime>? = null,
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
        timetableId: Long,
        request: TimetableLectureAddRequest,
    ): TimetableDisplay {
        // 동시 추가가 중복·겹침 검증을 통과하는 경쟁을 막기 위해 시간표 행을 잠그고 시작한다
        val timetable =
            timetableRepository.findByIdAndUserIdForUpdate(timetableId, userId)
                ?: throw SnuttException(ErrorType.TIMETABLE_NOT_FOUND)
        val lecture =
            lectureRepository.findByIdOrNull(request.lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
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
        timetableId: Long,
        request: CustomTimetableLectureAddRequest,
    ): TimetableDisplay {
        // 동시 추가가 겹침 검증을 통과하는 경쟁을 막기 위해 시간표 행을 잠그고 시작한다
        val timetable =
            timetableRepository.findByIdAndUserIdForUpdate(timetableId, userId)
                ?: throw SnuttException(ErrorType.TIMETABLE_NOT_FOUND)
        if (ClassTimeUtils.timesOverlap(request.classPlaceAndTimes)) throw SnuttException(ErrorType.INVALID_TIME)

        resolveTimeConflict(timetable, request.classPlaceAndTimes, request.isForced, null)

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
        val timetable = timetableService.getTimetable(userId, timetableId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureId)
        val existingDisplays = timetableService.displaysOf(listOf(timetable))[timetable.id!!].orEmpty()

        val newTimes =
            request.classPlaceAndTimes
                ?: existingDisplays.first { it.id == timetableLecture.id }.classPlaceAndTimes
        if (ClassTimeUtils.timesOverlap(newTimes)) throw SnuttException(ErrorType.INVALID_TIME)
        resolveTimeConflict(timetable, newTimes, request.isForced, timetableLecture.id)

        request.color?.let { timetableLecture.color = it }
        request.colorIndex?.let { timetableLecture.colorIndex = it }

        timetableLecture.updateOverrides { o ->
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
        }

        timetableLectureReminderService.recomputeForTimetableLecture(timetableLecture.id!!, newTimes)
        return displayAfterLectureChange(userId, timetable)
    }

    @Transactional
    fun resetLecture(
        userId: Long,
        timetableId: Long,
        timetableLectureId: Long,
        isForced: Boolean,
    ): TimetableDisplay {
        val timetable = timetableService.getTimetable(userId, timetableId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureId)
        if (timetableLecture.lectureId == null) throw SnuttException(ErrorType.CANNOT_RESET_CUSTOM_LECTURE)
        val lecture =
            lectureRepository.findByIdOrNull(timetableLecture.lectureId!!) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)

        val classTimes = lectureService.classTimesByLectureId(listOf(lecture.id!!))[lecture.id!!].orEmpty()
        resolveTimeConflict(timetable, classTimes, isForced, timetableLecture.id)

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
        val timetable = timetableService.getTimetable(userId, timetableId)
        val timetableLecture = getTimetableLecture(timetable, timetableLectureId)
        timetableLectureRepository.delete(timetableLecture)
        return displayAfterLectureChange(userId, timetable)
    }

    private fun displayAfterLectureChange(
        userId: Long,
        timetable: Timetable,
    ): TimetableDisplay {
        timetableRepository.touchUpdatedAt(timetable.id!!)
        return timetableService.getTimetableDisplay(userId, timetable.id!!)
    }

    fun getTimetableLecture(
        timetable: Timetable,
        timetableLectureId: Long,
    ): TimetableLecture =
        timetableLectureRepository.findByIdAndTimetableId(timetableLectureId, timetable.id!!)
            ?: throw SnuttException(ErrorType.TIMETABLE_LECTURE_NOT_FOUND)

    private fun resolveTimeConflict(
        timetable: Timetable,
        newTimes: List<ClassPlaceAndTime>,
        isForced: Boolean,
        selfId: Long?,
    ) {
        val displays = timetableService.displaysOf(listOf(timetable))[timetable.id!!].orEmpty()
        val overlapping =
            displays.filter { display ->
                display.id != selfId && ClassTimeUtils.timesOverlap(newTimes, display.classPlaceAndTimes)
            }
        if (overlapping.isEmpty()) return
        if (!isForced) {
            val confirmMessage = makeOverwritingConfirmMessage(overlapping)
            throw SnuttException(ErrorType.LECTURE_TIME_OVERLAP, errorMessage = confirmMessage, displayMessage = confirmMessage)
        }
        val overlappingIds = overlapping.map { it.id }
        timetableLectureRepository.deleteAll(
            timetableLectureRepository.findByTimetableId(timetable.id!!).filter { it.id in overlappingIds },
        )
    }

    private fun makeOverwritingConfirmMessage(overlappingLectures: List<TimetableLectureDisplay>): String {
        val overlappingLectureTitles = overlappingLectures.map { "'${it.courseTitle}'" }.take(2).joinToString(", ")
        val shortFormOfTitles = if (overlappingLectures.size < 3) "" else "외 ${overlappingLectures.size - 2}개의 "
        return "$overlappingLectureTitles ${shortFormOfTitles}강의와 시간이 겹칩니다. 강의를 덮어씌우겠습니까?"
    }
}
