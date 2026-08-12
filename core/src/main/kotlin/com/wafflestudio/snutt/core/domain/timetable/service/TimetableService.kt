package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.theme.dto.TimetableThemeDisplay
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableBriefDto
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLecture
import com.wafflestudio.snutt.core.domain.timetable.model.TimetableLectureCustomization
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureCustomizationRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TimetableService(
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val timetableLectureCustomizationRepository: TimetableLectureCustomizationRepository,
    private val lectureRepository: LectureRepository,
    private val coursebookService: CoursebookService,
    private val timetableThemeService: TimetableThemeService,
) {
    fun getTimetables(userId: Long): List<Timetable> = timetableRepository.findByUserId(userId)

    fun getMostRecentlyUpdatedTimetable(userId: Long): Timetable =
        timetableRepository.findFirstByUserIdOrderByUpdatedAtDesc(userId) ?: throw SnuttException(ErrorType.TIMETABLE_NOT_FOUND)

    fun getTimetablesBySemester(
        userId: Long,
        year: Int,
        semester: Semester,
    ): List<Timetable> = timetableRepository.findByUserIdAndYearAndSemester(userId, year, semester)

    fun getTimetable(
        userId: Long,
        timetableExternalId: String,
    ): Timetable =
        timetableRepository.findByUserIdAndExternalId(userId, timetableExternalId)
            ?: throw SnuttException(ErrorType.TIMETABLE_NOT_FOUND)

    // 시간표 + 병합된 강의 표시 목록 (lecture 최신 데이터 + customization override)
    fun getTimetableDisplay(
        userId: Long,
        timetableExternalId: String,
    ): TimetableDisplay {
        val timetable = getTimetable(userId, timetableExternalId)
        return TimetableDisplay(
            timetable = timetable,
            lectures = displaysOf(listOf(timetable))[timetable.id!!].orEmpty(),
            themeExternalId = timetable.themeId?.let(timetableThemeService::findThemeExternalId),
        )
    }

    fun toBriefs(timetables: List<Timetable>): List<TimetableBriefDto> {
        val displays = displaysOf(timetables)
        return timetables.map { timetable ->
            TimetableBriefDto(timetable, displays[timetable.id]?.sumOf { it.credit ?: 0 } ?: 0)
        }
    }

    // 배치 조회: N+1 없이 여러 시간표의 병합 표시를 만든다
    fun displaysOf(timetables: List<Timetable>): Map<Long, List<TimetableLectureDisplay>> {
        val timetableIds = timetables.mapNotNull { it.id }
        val lectures = timetableLectureRepository.findByTimetableIdIn(timetableIds)
        val lectureMap =
            lectureRepository.findAllById(lectures.mapNotNull { it.lectureId }).associateBy { it.id!! }
        val customizationMap =
            timetableLectureCustomizationRepository
                .findByTimetableLectureIdIn(lectures.mapNotNull { it.id })
                .associateBy { it.timetableLectureId }
        return lectures
            .groupBy { it.timetableId }
            .mapValues { (_, lectureList) ->
                lectureList.map { TimetableLectureDisplay(it, lectureMap[it.lectureId], customizationMap[it.id]) }
            }
    }

    @Transactional
    fun addTimetable(
        userId: Long,
        year: Int,
        semester: Semester,
        title: String,
    ): Timetable {
        validateTimetableTitle(userId, year, semester, title)
        val defaultTheme = timetableThemeService.getDefaultTheme(userId)
        return timetableRepository.save(
            Timetable(
                userId = userId,
                year = year,
                semester = semester,
                title = title,
                theme = defaultTheme.toTimetableTheme(),
                themeId = defaultTheme.id?.let(timetableThemeService::findThemeId),
                isPrimary = timetableRepository.findByUserIdAndYearAndSemester(userId, year, semester).isEmpty(),
            ),
        )
    }

    @Transactional
    fun modifyTimetableTitle(
        userId: Long,
        timetableExternalId: String,
        title: String,
    ): Timetable {
        val timetable = getTimetable(userId, timetableExternalId)
        validateTimetableTitle(userId, timetable.year, timetable.semester, title)
        timetable.title = title
        return timetable
    }

    @Transactional
    fun deleteTimetable(
        userId: Long,
        timetableExternalId: String,
    ) {
        if (timetableRepository.countByUserId(userId) <= 1L) throw SnuttException(ErrorType.TABLE_DELETE_ERROR)
        timetableRepository.delete(getTimetable(userId, timetableExternalId))
    }

    @Transactional
    fun copyTimetable(
        userId: Long,
        timetableExternalId: String,
        title: String? = null,
    ): Timetable {
        val timetable = getTimetable(userId, timetableExternalId)
        val baseTitle = (title ?: timetable.title).replace(COPY_NUMBER_REGEX, "")
        val lastCopiedNumber =
            timetableRepository
                .findByUserIdAndYearAndSemester(userId, timetable.year, timetable.semester)
                .mapNotNull {
                    it.title
                        .replace(baseTitle, "")
                        .filter(Char::isDigit)
                        .toIntOrNull()
                }.maxOrNull() ?: 0
        val copied =
            timetableRepository.save(
                Timetable(
                    userId = userId,
                    year = timetable.year,
                    semester = timetable.semester,
                    title = "$baseTitle (${lastCopiedNumber + 1})",
                    theme = timetable.theme,
                    themeId = timetable.themeId,
                    isPrimary = false,
                ),
            )

        // 강의 항목은 같은 lecture 참조를 복사하고 customization은 딸려 간다. 리마인더는 복사하지 않는다 (v1 동일)
        val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!)
        val customizations =
            timetableLectureCustomizationRepository
                .findByTimetableLectureIdIn(lectures.mapNotNull { it.id })
                .associateBy { it.timetableLectureId }
        val copiedLectures =
            lectures.map { source ->
                timetableLectureRepository.save(
                    TimetableLecture(
                        timetableId = copied.id!!,
                        lectureId = source.lectureId,
                        color = source.color,
                        colorIndex = source.colorIndex,
                    ),
                )
            }
        lectures.zip(copiedLectures).forEach { (source, copiedLecture) ->
            customizations[source.id]?.let { customization ->
                timetableLectureCustomizationRepository.save(
                    TimetableLectureCustomization(
                        timetableLectureId = copiedLecture.id!!,
                        courseTitle = customization.courseTitle,
                        instructor = customization.instructor,
                        credit = customization.credit,
                        remark = customization.remark,
                        classPlaceAndTime = customization.classPlaceAndTime,
                    ),
                )
            }
        }
        return copied
    }

    @Transactional
    fun modifyTimetableTheme(
        userId: Long,
        timetableExternalId: String,
        theme: BasicThemeType?,
        themeExternalId: String?,
    ): TimetableDisplay {
        require((themeExternalId == null) xor (theme == null))
        val timetable = getTimetable(userId, timetableExternalId)
        val customThemeId = themeExternalId?.let { timetableThemeService.findThemeIdOwnedBy(userId, it) }
        timetable.theme = if (customThemeId != null) BasicThemeType.SNUTT else theme!!
        timetable.themeId = customThemeId

        // 테마 변경 시 모든 강의의 색상을 다시 부여한다 (v1 동일)
        val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!)
        val colors = customThemeId?.let { timetableThemeService.themeColors(it) }
        val colorCount = colors?.size ?: BasicThemeType.COLOR_COUNT
        lectures.forEachIndexed { index, timetableLecture ->
            if (colors != null) {
                timetableLecture.color = colors[index % colorCount]
                timetableLecture.colorIndex = 0
            } else {
                timetableLecture.color = null
                timetableLecture.colorIndex = (index % colorCount) + 1
            }
        }
        return TimetableDisplay(
            timetable = timetable,
            lectures = displaysOf(listOf(timetable))[timetable.id!!].orEmpty(),
            themeExternalId = customThemeId?.let(timetableThemeService::findThemeExternalId),
        )
    }

    @Transactional
    fun setPrimary(
        userId: Long,
        timetableExternalId: String,
    ) {
        val newPrimary = getTimetable(userId, timetableExternalId)
        if (newPrimary.isPrimary) return
        newPrimary.isPrimary = true
        timetableRepository
            .findByUserIdAndYearAndSemesterAndIsPrimaryTrue(userId, newPrimary.year, newPrimary.semester)
            ?.let { it.isPrimary = false }
    }

    @Transactional
    fun unsetPrimary(
        userId: Long,
        timetableExternalId: String,
    ) {
        val timetable = getTimetable(userId, timetableExternalId)
        if (!timetable.isPrimary) return
        timetable.isPrimary = false
    }

    fun getUserPrimaryTable(
        userId: Long,
        year: Int,
        semester: Semester,
    ): Timetable =
        timetableRepository.findByUserIdAndYearAndSemesterAndIsPrimaryTrue(userId, year, semester)
            ?: throw SnuttException(ErrorType.PRIMARY_TIMETABLE_NOT_FOUND)

    fun getCoursebooksWithPrimaryTable(userId: Long): List<Pair<Int, Semester>> =
        timetableRepository
            .findByUserIdAndIsPrimaryTrue(userId)
            .map { it.year to it.semester }
            .distinct()
            .sortedWith(compareByDescending<Pair<Int, Semester>> { it.first }.thenByDescending { it.second.value })

    @Transactional
    fun createDefaultTable(userId: Long): Timetable {
        val coursebook = coursebookService.getLatestCoursebook()
        val defaultTheme = timetableThemeService.getDefaultTheme(userId)
        return timetableRepository.save(
            Timetable(
                userId = userId,
                year = coursebook.year,
                semester = coursebook.semester,
                title = "나의 시간표",
                theme = defaultTheme.toTimetableTheme(),
                themeId = defaultTheme.id?.let(timetableThemeService::findThemeId),
            ),
        )
    }

    private fun validateTimetableTitle(
        userId: Long,
        year: Int,
        semester: Semester,
        title: String,
    ) {
        if (title.isEmpty()) throw SnuttException(ErrorType.INVALID_TIMETABLE_TITLE)
        if (!coursebookService.existsCoursebook(year, semester)) throw SnuttException(ErrorType.INVALID_TIMETABLE_SEMESTER)
        if (timetableRepository.findByUserIdAndYearAndSemesterAndTitle(userId, year, semester, title) != null) {
            throw SnuttException(ErrorType.DUPLICATE_TIMETABLE_TITLE)
        }
    }

    private fun TimetableThemeDisplay.toTimetableTheme(): BasicThemeType =
        if (isCustom) BasicThemeType.SNUTT else BasicThemeType.from(name)!!

    companion object {
        private val COPY_NUMBER_REGEX = """\s\(\d+\)$""".toRegex()
    }
}
