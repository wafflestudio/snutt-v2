package com.wafflestudio.snutt.core.domain.bookmark.repository

import com.wafflestudio.snutt.core.domain.bookmark.model.BookmarkLecture
import org.springframework.data.jpa.repository.JpaRepository

interface BookmarkLectureRepository : JpaRepository<BookmarkLecture, Long> {
    fun findByBookmarkId(bookmarkId: Long): List<BookmarkLecture>

    fun findByBookmarkIdAndLectureId(
        bookmarkId: Long,
        lectureId: Long,
    ): BookmarkLecture?

    fun existsByBookmarkIdAndLectureId(
        bookmarkId: Long,
        lectureId: Long,
    ): Boolean

    fun deleteByBookmarkIdAndLectureId(
        bookmarkId: Long,
        lectureId: Long,
    )

    fun findByLectureIdIn(lectureIds: Collection<Long>): List<BookmarkLecture>
}
