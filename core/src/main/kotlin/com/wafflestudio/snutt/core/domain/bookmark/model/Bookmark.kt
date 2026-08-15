package com.wafflestudio.snutt.core.domain.bookmark.model

import com.wafflestudio.snutt.core.common.enums.Semester
import com.wafflestudio.snutt.core.common.model.ExternalIdEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

// 학기별 북마크 묶음. 강의는 bookmark_lecture가 FK 참조한다
@Entity
@Table(name = "bookmark")
class Bookmark(
    var userId: Long,
    var year: Int,
    @JdbcTypeCode(SqlTypes.TINYINT)
    var semester: Semester,
) : ExternalIdEntity()
