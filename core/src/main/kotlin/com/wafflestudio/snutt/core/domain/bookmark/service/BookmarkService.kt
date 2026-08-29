package com.wafflestudio.snutt.core.domain.bookmark.service

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.error.ErrorType
import com.wafflestudio.snutt.core.common.error.SnuttException
import com.wafflestudio.snutt.core.domain.bookmark.model.BookmarkLecture
import com.wafflestudio.snutt.core.domain.bookmark.repository.BookmarkLectureRepository
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
    private val bookmarkLectureRepository: BookmarkLectureRepository,
    private val lectureRepository: LectureRepository,
) {
    fun getBookmark(
        userId: Long,
        year: Int,
        semester: Semester,
    ): BookmarkDisplay {
        val entries = bookmarkLectureRepository.findByUserIdAndYearAndSemester(userId, year, semester)
        val lectures = lectureRepository.findAllById(entries.map { it.lectureId })
        return BookmarkDisplay(year = year, semester = semester, lectures = lectures)
    }

    fun existsBookmarkLecture(
        userId: Long,
        lectureId: Long,
    ): Boolean {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        return bookmarkLectureRepository.existsByUserIdAndYearAndSemesterAndLectureId(
            userId,
            lecture.year,
            lecture.semester,
            lecture.id!!,
        )
    }

    @Transactional
    fun addLecture(
        userId: Long,
        lectureId: Long,
    ) {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        val exists =
            bookmarkLectureRepository.existsByUserIdAndYearAndSemesterAndLectureId(
                userId,
                lecture.year,
                lecture.semester,
                lecture.id!!,
            )
        if (!exists) {
            bookmarkLectureRepository.save(
                BookmarkLecture(
                    userId = userId,
                    year = lecture.year,
                    semester = lecture.semester,
                    lectureId = lecture.id!!,
                ),
            )
        }
    }

    @Transactional
    fun deleteLecture(
        userId: Long,
        lectureId: Long,
    ) {
        val lecture = lectureRepository.findByIdOrNull(lectureId) ?: throw SnuttException(ErrorType.LECTURE_NOT_FOUND)
        bookmarkLectureRepository.deleteByUserIdAndYearAndSemesterAndLectureId(userId, lecture.year, lecture.semester, lecture.id!!)
    }
}
