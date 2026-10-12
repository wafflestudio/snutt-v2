package com.wafflestudio.snutt.core.domain.bookmark.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.bookmark.model.BookmarkLecture
import com.wafflestudio.snutt.core.domain.lecture.model.Lecture
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BookmarkLectureRepository : JpaRepository<BookmarkLecture, Long> {
    @Query(
        "SELECT l FROM Lecture l JOIN BookmarkLecture b ON b.lectureId = l.id " +
            "WHERE b.userId = :userId AND l.year = :year AND l.semester = :semester ORDER BY l.id",
    )
    fun findLectures(
        userId: Long,
        year: Int,
        semester: Semester,
    ): List<Lecture>

    fun existsByUserIdAndLectureId(
        userId: Long,
        lectureId: Long,
    ): Boolean

    fun deleteByUserIdAndLectureId(
        userId: Long,
        lectureId: Long,
    )

    fun findByLectureIdIn(lectureIds: Collection<Long>): List<BookmarkLecture>
}
