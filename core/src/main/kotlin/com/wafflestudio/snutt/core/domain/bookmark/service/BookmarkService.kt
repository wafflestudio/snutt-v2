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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class BookmarkDisplay(
    val year: Int,
    val semester: Semester,
    val lectures: List<Lecture>,
)

@Service
class BookmarkService(
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkLectureRepository: BookmarkLectureRepository,
    private val lectureRepository: LectureRepository,
) {
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
        lectureId: Long,
    ): Boolean {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val bookmark = bookmarkRepository.findByUserIdAndYearAndSemester(userId, lecture.year, lecture.semester) ?: return false
        return bookmarkLectureRepository.existsByBookmarkIdAndLectureId(bookmark.id!!, lecture.id!!)
    }

    @Transactional
    fun addLecture(
        userId: Long,
        lectureId: Long,
    ) {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        addLecture(userId, lecture)
    }

    @Transactional
    fun deleteLecture(
        userId: Long,
        lectureId: Long,
    ) {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        deleteLecture(userId, lecture)
    }

    private fun addLecture(
        userId: Long,
        lecture: Lecture,
    ) {
        val bookmark =
            bookmarkRepository.findByUserIdAndYearAndSemester(userId, lecture.year, lecture.semester)
                ?: bookmarkRepository.save(Bookmark(userId = userId, year = lecture.year, semester = lecture.semester))
        val bookmarkId = bookmark.id!!
        if (!bookmarkLectureRepository.existsByBookmarkIdAndLectureId(bookmarkId, lecture.id!!)) {
            bookmarkLectureRepository.save(BookmarkLecture(bookmarkId = bookmarkId, lectureId = lecture.id!!))
        }
    }

    private fun deleteLecture(
        userId: Long,
        lecture: Lecture,
    ) {
        val bookmark = bookmarkRepository.findByUserIdAndYearAndSemester(userId, lecture.year, lecture.semester) ?: return
        bookmarkLectureRepository.deleteByBookmarkIdAndLectureId(bookmark.id!!, lecture.id!!)
    }
}
