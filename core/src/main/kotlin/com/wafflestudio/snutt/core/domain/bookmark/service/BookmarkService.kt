package com.wafflestudio.snutt.core.domain.bookmark.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.bookmark.model.Bookmark
import com.wafflestudio.snutt.core.domain.bookmark.model.BookmarkLecture
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class BookmarkDisplay(
    val year: Int,
    val semester: Semester,
    // 북마크된 강의 표시 (id = lecture external_id)
    val lectures: List<Lecture>,
)

@Service
class BookmarkService(
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkLectureRepository: BookmarkLectureRepository,
    private val lectureRepository: LectureRepository,
) {
    // 북마크가 없으면 빈 목록을 반환한다 (v1 동일)
    fun getBookmark(
        userId: Long,
        year: Int,
        semester: Semester,
    ): BookmarkDisplay {
        val bookmark = bookmarkRepository.findByUserIdAndYearAndSemester(userId, year, semester)
        val lectures =
            bookmark
                ?.let {
                    val lectureIds = bookmarkLectureRepository.findByBookmarkId(it.id!!).map { l -> l.lectureId }
                    lectureRepository.findAllById(lectureIds)
                }.orEmpty()
        return BookmarkDisplay(year = year, semester = semester, lectures = lectures)
    }

    fun existsBookmarkLecture(
        userId: Long,
        lectureExternalId: String,
    ): Boolean {
        val lecture = lectureRepository.findByExternalId(lectureExternalId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val bookmark = bookmarkRepository.findByUserIdAndYearAndSemester(userId, lecture.year, lecture.semester) ?: return false
        return bookmarkLectureRepository.existsByBookmarkIdAndLectureId(bookmark.id!!, lecture.id!!)
    }

    @Transactional
    fun addLecture(
        userId: Long,
        lectureExternalId: String,
    ) {
        val lecture = lectureRepository.findByExternalId(lectureExternalId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val bookmark =
            bookmarkRepository.findByUserIdAndYearAndSemester(userId, lecture.year, lecture.semester)
                ?: bookmarkRepository.save(Bookmark(userId = userId, year = lecture.year, semester = lecture.semester))
        val bookmarkId = bookmark.id!!
        if (!bookmarkLectureRepository.existsByBookmarkIdAndLectureId(bookmarkId, lecture.id!!)) {
            bookmarkLectureRepository.save(BookmarkLecture(bookmarkId = bookmarkId, lectureId = lecture.id!!))
        }
    }

    @Transactional
    fun deleteLecture(
        userId: Long,
        lectureExternalId: String,
    ) {
        val lecture = lectureRepository.findByExternalId(lectureExternalId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val bookmark = bookmarkRepository.findByUserIdAndYearAndSemester(userId, lecture.year, lecture.semester) ?: return
        bookmarkLectureRepository.deleteByBookmarkIdAndLectureId(bookmark.id!!, lecture.id!!)
    }
}
