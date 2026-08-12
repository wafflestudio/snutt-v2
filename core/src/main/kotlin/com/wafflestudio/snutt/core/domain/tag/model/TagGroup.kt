package com.wafflestudio.snutt.core.domain.tag.model

import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

enum class TagValueType {
    INT,
    STRING,
    LOGIC,
}

@Entity
@Table(name = "tag_group")
class TagGroup(
    var name: String,
    var ordering: Int,
    var color: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "value_type")
    var valueType: TagValueType,
) : BaseEntity()
