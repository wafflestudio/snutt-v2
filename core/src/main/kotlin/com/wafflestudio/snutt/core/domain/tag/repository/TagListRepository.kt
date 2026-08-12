package com.wafflestudio.snutt.core.domain.tag.repository

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.domain.tag.model.TagList
import org.springframework.data.jpa.repository.JpaRepository

interface TagListRepository : JpaRepository<TagList, Long> {
    fun findByYearAndSemester(
        year: Int,
        semester: Semester,
    ): TagList?
}
