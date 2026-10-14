package com.wafflestudio.snutt.core.domain.bookmark.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity

@Entity
class BookmarkLecture(
    var userId: Long,
    var lectureId: Long,
) : BaseEntity()
