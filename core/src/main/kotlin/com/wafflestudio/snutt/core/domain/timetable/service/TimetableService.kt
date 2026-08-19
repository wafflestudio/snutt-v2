package com.wafflestudio.snutt.core.domain.timetable.service

import com.wafflestudio.snutt.core.common.enums.BasicThemeType
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.coursebook.service.CoursebookService
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.theme.service.TimetableThemeService
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableBriefDto
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableDisplay
import com.wafflestudio.snutt.core.domain.timetable.dto.TimetableLectureDisplay
import com.wafflestudio.snutt.core.domain.timetable.model.Timetable
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableLectureRepository
import com.wafflestudio.snutt.core.domain.timetable.repository.TimetableRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TimetableService(
    private val timetableRepository: TimetableRepository,
    private val timetableLectureRepository: TimetableLectureRepository,
    private val lectureRepository: LectureRepository,
    private val lectureService: LectureService,
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
        timetableId: Long,
    ): Timetable = timetableRepository.findByIdAndUserId(timetableId, userId) ?: throw SnuttException(ErrorType.TIMETABLE_NOT_FOUND)

    fun getTimetableDisplay(
        userId: Long,
        timetableId: Long,
    ): TimetableDisplay = displayOf(getTimetable(userId, timetableId))

    fun displayOf(timetable: Timetable): TimetableDisplay =
        TimetableDisplay(
            timetable = timetable,
            lectures = displaysOf(listOf(timetable))[timetable.id!!].orEmpty(),
        )

    fun toBriefs(timetables: List<Timetable>): List<TimetableBriefDto> {
        val displays = displaysOf(timetables)
        return timetables.map { timetable ->
            TimetableBriefDto(timetable, displays[timetable.id]?.sumOf { it.credit ?: 0 } ?: 0)
        }
    }

    fun displaysOf(timetables: List<Timetable>): Map<Long, List<TimetableLectureDisplay>> {
        val timetableIds = timetables.mapNotNull { it.id }
        val lectures = timetableLectureRepository.findByTimetableIdIn(timetableIds)
        val lectureMap =
            lectureRepository.findAllById(lectures.mapNotNull { it.lectureId }).associateBy { it.id!! }
        val classTimesMap =
            lectureService.classTimesByLectureId(lectures.mapNotNull { it.lectureId })
        return lectures
            .groupBy { it.timetableId }
            .mapValues { (_, lectureList) ->
                lectureList.map { TimetableLectureDisplay(it, lectureMap[it.lectureId], classTimesMap[it.lectureId].orEmpty()) }
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
        return timetableRepository.save(
            Timetable(
                userId = userId,
                year = year,
                semester = semester,
                title = title,
                themeId = timetableThemeService.getDefaultThemeId(userId),
                isPrimary = timetableRepository.findByUserIdAndYearAndSemester(userId, year, semester).isEmpty(),
            ),
        )
    }

    @Transactional
    fun modifyTimetableTitle(
        userId: Long,
        timetableId: Long,
        title: String,
    ): Timetable {
        val timetable = getTimetable(userId, timetableId)
        validateTimetableTitle(userId, timetable.year, timetable.semester, title)
        timetable.title = title
        return timetable
    }

    @Transactional
    fun deleteTimetable(
        userId: Long,
        timetableId: Long,
    ) {
        if (timetableRepository.countByUserId(userId) <= 1L) throw SnuttException(ErrorType.TABLE_DELETE_ERROR)
        timetableRepository.delete(getTimetable(userId, timetableId))
    }

    @Transactional
    fun copyTimetable(
        userId: Long,
        timetableId: Long,
        title: String? = null,
    ): Timetable {
        val timetable = getTimetable(userId, timetableId)
        val baseTitle = (title ?: timetable.title).replace(COPY_NUMBER_REGEX, "")
        val copyNumber = Regex("^${Regex.escape(baseTitle)} \\((\\d+)\\)$")
        val lastCopiedNumber =
            timetableRepository
                .findByUserIdAndYearAndSemester(userId, timetable.year, timetable.semester)
                .mapNotNull {
                    copyNumber
                        .find(it.title)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                }.maxOrNull() ?: 0
        val copied =
            timetableRepository.save(
                Timetable(
                    userId = userId,
                    year = timetable.year,
                    semester = timetable.semester,
                    title = "$baseTitle (${lastCopiedNumber + 1})",
                    themeId = timetable.themeId,
                    isPrimary = false,
                ),
            )

        timetableLectureRepository.findByTimetableId(timetable.id!!).forEach { source ->
            timetableLectureRepository.save(source.copyFor(copied.id!!))
        }
        return copied
    }

    @Transactional
    fun modifyTimetableTheme(
        userId: Long,
        timetableId: Long,
        themeId: Long,
    ): TimetableDisplay {
        val timetable = getTimetable(userId, timetableId)
        timetable.themeId = timetableThemeService.findThemeById(themeId).id!!

        val theme = timetableThemeService.findThemeById(timetable.themeId)
        val lectures = timetableLectureRepository.findByTimetableId(timetable.id!!)
        if (theme.isBuiltin) {
            lectures.forEachIndexed { index, timetableLecture ->
                timetableLecture.color = null
                timetableLecture.colorIndex = (index % BasicThemeType.COLOR_COUNT) + 1
            }
        } else {
            val colors = theme.colors
            lectures.forEachIndexed { index, timetableLecture ->
                timetableLecture.color = colors[index % colors.size]
                timetableLecture.colorIndex = 0
            }
        }
        return displayOf(timetable)
    }

    @Transactional
    fun setPrimary(
        userId: Long,
        timetableId: Long,
    ) {
        val newPrimary = getTimetable(userId, timetableId)
        if (newPrimary.isPrimary) return
        timetableRepository
            .findByUserIdAndYearAndSemesterAndIsPrimaryTrue(userId, newPrimary.year, newPrimary.semester)
            ?.let { it.isPrimary = false }
        newPrimary.isPrimary = true
    }

    @Transactional
    fun unsetPrimary(
        userId: Long,
        timetableId: Long,
    ) {
        val timetable = getTimetable(userId, timetableId)
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
        val defaultThemeId = timetableThemeService.getDefaultThemeId(userId)
        return timetableRepository.save(
            Timetable(
                userId = userId,
                year = coursebook.year,
                semester = coursebook.semester,
                title = "나의 시간표",
                themeId = defaultThemeId,
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

    companion object {
        private val COPY_NUMBER_REGEX = """\s\(\d+\)$""".toRegex()
    }
}
