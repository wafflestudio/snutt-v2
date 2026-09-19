package com.wafflestudio.snutt.api.v2.bookmark

import com.wafflestudio.snutt.api.auth.CurrentUserId
import com.wafflestudio.snutt.api.v2.lecture.LectureResponse
import com.wafflestudio.snutt.api.v2.lecture.toResponse
import com.wafflestudio.snutt.core.common.client.ClientInfo
import com.wafflestudio.snutt.core.common.client.Language
import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkDisplay
import com.wafflestudio.snutt.core.domain.bookmark.service.BookmarkService
import com.wafflestudio.snutt.core.domain.lecture.model.ClassPlaceAndTime
import com.wafflestudio.snutt.core.domain.lecture.service.LectureService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class BookmarkResponse(
    val year: Int,
    val semester: Semester,
    val lectures: List<LectureResponse>,
)

private fun BookmarkDisplay.toResponse(
    classTimesMap: Map<Long, List<ClassPlaceAndTime>>,
    language: Language,
) = BookmarkResponse(
    year = year,
    semester = semester,
    lectures = lectures.map { it.toResponse(classTimesMap[it.id].orEmpty(), language) },
)

@RestController
@RequestMapping("/v2/bookmarks")
class BookmarkController(
    private val bookmarkService: BookmarkService,
    private val lectureService: LectureService,
) {
    @GetMapping("")
    fun getBookmarks(
        @CurrentUserId userId: Long,
        @RequestParam year: Int,
        @RequestParam semester: Semester,
        @RequestAttribute clientInfo: ClientInfo,
    ): BookmarkResponse {
        val display = bookmarkService.getBookmark(userId, year, semester)
        val classTimesMap = lectureService.classTimesByLectureId(display.lectures.map { it.id!! })
        return display.toResponse(classTimesMap, clientInfo.language)
    }

    @GetMapping("/lectures/{lectureId}/state")
    fun existsBookmarkLecture(
        @CurrentUserId userId: Long,
        @PathVariable lectureId: Long,
    ): Boolean = bookmarkService.existsBookmarkLecture(userId, lectureId)

    @PostMapping("/lectures/{lectureId}")
    fun addLecture(
        @CurrentUserId userId: Long,
        @PathVariable lectureId: Long,
    ) {
        bookmarkService.addLecture(userId, lectureId)
    }

    @DeleteMapping("/lectures/{lectureId}")
    fun deleteLecture(
        @CurrentUserId userId: Long,
        @PathVariable lectureId: Long,
    ) {
        bookmarkService.deleteLecture(userId, lectureId)
    }
}
