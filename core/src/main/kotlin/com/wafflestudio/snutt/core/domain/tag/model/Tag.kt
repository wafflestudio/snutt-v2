package com.wafflestudio.snutt.core.domain.tag.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "tag")
class Tag(
    var tagGroupId: Long,
    var name: String,
    var description: String? = null,
    var ordering: Int,
    var intValue: Int? = null,
    var stringValue: String? = null,
) : BaseEntity()
