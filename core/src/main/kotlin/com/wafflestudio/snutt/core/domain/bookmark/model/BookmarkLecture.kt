package com.wafflestudio.snutt.core.domain.bookmark.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "bookmark_lecture")
class BookmarkLecture(
    var userId: Long,
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
    var lectureId: Long,
) : BaseEntity()
