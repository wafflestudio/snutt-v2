package com.wafflestudio.snutt.api.v2.bookmark

import com.wafflestudio.snutt.api.auth.CurrentUser
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.client.select
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkDisplay
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkService
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import com.wafflestudio.snutt.core.domain.user.model.User
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class BookmarkResponse(
    val year: Int,
    val semester: Semester,
    val lectures: List<BookmarkLectureResponse>,
)

data class BookmarkLectureResponse(
    val id: Long,
    val academicYear: String?,
    val category: String?,
    val categoryPre2025: String?,
    val classification: String?,
    val courseNumber: String,
    val lectureNumber: String,
    val department: String?,
    val quota: Int,
    val freshmanQuota: Int?,
    val courseTitle: String,
    val instructor: String?,
    val credit: Int,
    val remark: String?,
    val classPlaceAndTimes: List<BookmarkClassPlaceAndTimeResponse>,
)

data class BookmarkClassPlaceAndTimeResponse(
    val day: Int,
    val place: String,
    val startMinute: Int,
    val endMinute: Int,
)

data class BookmarkLectureModifyRequest(
    val lectureId: Long,
)

private fun BookmarkDisplay.toResponse(
    classTimesMap: Map<Long, List<ClassPlaceAndTime>>,
    language: Language,
) = BookmarkResponse(
    year = year,
    semester = semester,
    lectures = lectures.map { it.toResponse(classTimesMap[it.id].orEmpty(), language) },
)

private fun Lecture.toResponse(
    classTimes: List<ClassPlaceAndTime>,
    language: Language,
) = BookmarkLectureResponse(
    id = id!!,
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
    classPlaceAndTimes = classTimes.map { it.toResponse() },
)

private fun ClassPlaceAndTime.toResponse() =
    BookmarkClassPlaceAndTimeResponse(day = day.value, place = place, startMinute = startMinute, endMinute = endMinute)

@RestController
@RequestMapping("/v2/bookmarks")
class BookmarkController(
    private val bookmarkService: BookmarkService,
    private val lectureService: LectureService,
) {
    @GetMapping("")
    fun getBookmarks(
        @CurrentUser user: User,
        @RequestParam year: Int,
        @RequestParam semester: Int,
        @RequestAttribute clientInfo: ClientInfo,
    ): BookmarkResponse {
        val display =
            bookmarkService.getBookmark(user.id!!, year, Semester.getOfValue(semester) ?: throw SnuttException(ErrorType.INVALID_PARAMETER))
        val classTimesMap = lectureService.classTimesByLectureId(display.lectures.mapNotNull { it.id })
        return display.toResponse(classTimesMap, clientInfo.language)
    }

    @GetMapping("/lectures/{lectureId}/state")
    fun existsBookmarkLecture(
        @CurrentUser user: User,
        @PathVariable lectureId: Long,
    ): Boolean = bookmarkService.existsBookmarkLecture(user.id!!, lectureId)

    @PostMapping("/lecture")
    fun addLecture(
        @CurrentUser user: User,
        @RequestBody body: BookmarkLectureModifyRequest,
    ) {
        bookmarkService.addLecture(user.id!!, body.lectureId)
    }

    @DeleteMapping("/lecture")
    fun deleteLecture(
        @CurrentUser user: User,
        @RequestBody body: BookmarkLectureModifyRequest,
    ) {
        bookmarkService.deleteLecture(user.id!!, body.lectureId)
    }
}
