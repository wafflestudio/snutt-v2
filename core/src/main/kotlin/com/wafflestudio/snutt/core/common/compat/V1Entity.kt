package com.wafflestudio.snutt.core.common.compat

import com.fasterxml.jackson.annotation.JsonIgnore
import com.wafflestudio.snutt.core.common.model.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import org.bson.types.ObjectId

@MappedSuperclass
abstract class V1Entity : BaseEntity() {
    @get:JsonIgnore
    @Column(name = "idv1", nullable = false, updatable = false, columnDefinition = "char(24)")
    val idV1: String = ObjectId().toHexString()
}
