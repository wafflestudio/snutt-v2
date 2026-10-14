package com.wafflestudio.snutt.core.domain.bookmark.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.bookmark.model.BookmarkLecture
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import com.wafflestudio.snutt.core.domain.lecture.repository.LectureRepository
import com.wafflestudio.snutt.core.domain.user.repository.UserRepository
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
    private val bookmarkLectureRepository: BookmarkLectureRepository,
    private val lectureRepository: LectureRepository,
    private val userRepository: UserRepository,
) {
    fun getBookmark(
        userId: Long,
        year: Int,
        semester: Semester,
    ): BookmarkDisplay = BookmarkDisplay(year, semester, bookmarkLectureRepository.findLectures(userId, year, semester))

    fun existsBookmarkLecture(
        userId: Long,
        lectureId: Long,
    ): Boolean {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        return bookmarkLectureRepository.existsByUserIdAndLectureId(userId, lecture.id!!)
    }

    @Transactional
    fun addLecture(
        userId: Long,
        lectureId: Long,
    ) {
        userRepository.findForUpdateById(userId) ?: throw SnuttException(ErrorType.USER_NOT_FOUND)
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        if (!bookmarkLectureRepository.existsByUserIdAndLectureId(userId, lecture.id!!)) {
            bookmarkLectureRepository.save(BookmarkLecture(userId = userId, lectureId = lecture.id!!))
        }
    }

    @Transactional
    fun deleteLecture(
        userId: Long,
        lectureId: Long,
    ) {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        bookmarkLectureRepository.deleteByUserIdAndLectureId(userId, lecture.id!!)
    }
}
