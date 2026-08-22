package com.wafflestudio.snutt.core.domain.bookmark.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.bookmark.model.BookmarkLecture
import org.springframework.data.jpa.repository.JpaRepository

interface BookmarkLectureRepository : JpaRepository<BookmarkLecture, Long> {
    fun findByUserIdAndYearAndSemester(
        userId: Long,
        year: Int,
        semester: Semester,
    ): List<BookmarkLecture>

    fun existsByUserIdAndYearAndSemesterAndLectureId(
        userId: Long,
        year: Int,
        semester: Semester,
        lectureId: Long,
    ): Boolean

    fun deleteByUserIdAndYearAndSemesterAndLectureId(
        userId: Long,
        year: Int,
        semester: Semester,
        lectureId: Long,
    )

    fun findByLectureIdIn(lectureIds: Collection<Long>): List<BookmarkLecture>
}
