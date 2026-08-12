package com.wafflestudio.snutt.core.domain.bookmark.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "bookmark_lecture")
class BookmarkLecture(
    var bookmarkId: Long,
    var lectureId: Long,
) : BaseEntity()
