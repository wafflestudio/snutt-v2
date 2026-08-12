package com.wafflestudio.snutt.core.domain.bookmark.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.bookmark.model.Bookmark
import org.springframework.data.jpa.repository.JpaRepository

interface BookmarkRepository : JpaRepository<Bookmark, Long> {
    fun findByUserIdAndYearAndSemester(
        userId: Long,
        year: Int,
        semester: Semester,
    ): Bookmark?
}
